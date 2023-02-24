/*
 *  ******************************************************************************
 *  *
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Apache License, Version 2.0 which is available at
 *  * https://www.apache.org/licenses/LICENSE-2.0.
 *  *
 *  *  See the NOTICE file distributed with this work for additional
 *  *  information regarding copyright ownership.
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  * License for the specific language governing permissions and limitations
 *  * under the License.
 *  *
 *  * SPDX-License-Identifier: Apache-2.0
 *  *****************************************************************************
 */

package org.nd4j.linalg.api.memory.deallocation;

import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.RandomUtils;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.linalg.api.memory.Deallocatable;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles deallocation of offheap references.
 * This service starts a set of gc threads
 * and reference queues.
 * These reference queues are passed references to the jvm.
 *
 * The instances passed are of {@link Deallocatable}
 * These are all pointers with a deallocate() method which will be
 * called before being finally garbage collected by the JVM.
 *
 * The deallocator service handles generating unique ids by using an
 * atomic counter + an offset generated by {@link Deallocatable#getUniqueId()}
 *
 * Note that some buffers also may not be deallocated. Before deallocation a buffer
 * is checked for whether it's constant with {@link Deallocator#isConstant()}
 * If a reference is constant (this is usually only applicable to shape buffers)
 * then it will not be deallocated.
 *
 * You may turn the deallocator service off by setting {@link #noPointerGc}
 * to true with the system property: {@link ND4JSystemProperties#NO_ARRAY_GC}
 *
 * Note the service also checks "org.bytedeco.javacpp.noPointerGC"
 *
 * Also of note is you may access the references with {@link #getReferenceMap()}
 * DO THIS AT YOUR OWN RISK. ONLY FOR advanced users.
 *
 *
 * Known limitations:
 *
 * Due to relying on the JVM to supply garbage collectable references,
 * the allocator fall behind in deallocation. This happens VERY rarely and only at certain scales.
 *
 */
@Slf4j
public class DeallocatorService {
    private Thread[] deallocatorThreads;
    private ReferenceQueue<Deallocatable>[] queues;
    //note we do this for thread safety while reducing the amount of memory
    //strings take up. There is a performance trade off with respect to
    //ConcurrentHashMap (the prior implementation) which has higher throughput overall but is inefficient
    //for the amount of memory overhead it has. String compression
    //with a large number of objects is more important over throughput.
    @Getter
    private Map<Long,DeallocatableReference> referenceMap = new ConcurrentSkipListMap<>();
    private static Set<Long> deallocatedIds = new ConcurrentSkipListSet<>();

    private static AtomicBoolean blockDeallocator = new AtomicBoolean(false);

    private List<List<ReferenceQueue<Deallocatable>>> deviceMap = new ArrayList<>();
    private Boolean noPointerGc;
    private final transient AtomicLong counter = new AtomicLong(0);

    public DeallocatorService() {
        // we need to have at least 2 threads, but for CUDA we'd need at least numDevices threads, due to thread->device affinity
        int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
        int numThreads = 1;

        for (int e = 0; e < numDevices; e++)
            deviceMap.add(new ArrayList<>());

        deallocatorThreads = new Thread[numThreads];
        queues = new ReferenceQueue[numThreads];
        noPointerGc = Boolean.parseBoolean(System.getProperty(ND4JSystemProperties.NO_ARRAY_GC,"false")) || Boolean.parseBoolean(System.getProperty("org.bytedeco.javacpp.nopointergc","false"));
        if(!noPointerGc) {
            for (int e = 0; e < numThreads; e++) {
                log.trace("Starting deallocator thread {}", e + 1);
                queues[e] = new ReferenceQueue<>();

                int deviceId = e % numDevices;
                // attaching queue to its own thread
                deallocatorThreads[e] = new DeallocatorServiceThread(queues[e], e, deviceId);
                deallocatorThreads[e].setName("DeallocatorServiceThread_" + e);
                deallocatorThreads[e].setDaemon(true);

                deviceMap.get(deviceId).add(queues[e]);

                deallocatorThreads[e].start();
            }
        } else {
            log.warn("Disabling automatic garbage collection since the system property " + ND4JSystemProperties.NO_ARRAY_GC + " or " + " org.bytedeco.javacpp.nopointergc was set to false");
        }


    }


    public void toggleDeallocationBlock(boolean shouldBlock) {
        blockDeallocator.set(shouldBlock);
    }


    public long nextValue() {
        return counter.incrementAndGet();
    }

    /**
     * This method adds Deallocatable object instance to tracking system
     *
     * @param deallocatable object to track
     */
    public void pickObject(@NonNull Deallocatable deallocatable) {
        if(noPointerGc) {
            log.trace("Deallocation turned off. Reference " + deallocatable.getUniqueId() + " will need to be de allocated manually.");
        } else {
            val desiredDevice = deallocatable.targetDevice();
            val map = deviceMap.get(desiredDevice);
            val reference = new DeallocatableReference(deallocatable, map.get(RandomUtils.nextInt(0, map.size())));
            referenceMap.put(deallocatable.getUniqueId(), reference);

        }
    }


    private class DeallocatorServiceThread extends Thread implements Runnable {
        private final ReferenceQueue<Deallocatable> queue;
        private final int threadIdx;
        public static final String DeallocatorThreadNamePrefix = "DeallocatorServiceThread thread ";
        private final int deviceId;

        private DeallocatorServiceThread(@NonNull ReferenceQueue<Deallocatable> queue, int threadIdx, int deviceId) {
            this.queue = queue;
            this.threadIdx = threadIdx;
            this.setName(DeallocatorThreadNamePrefix + threadIdx);
            this.deviceId = deviceId;
            setContextClassLoader(null);
        }

        @SneakyThrows
        @Override
        public void run() {
            Nd4j.getAffinityManager().unsafeSetDevice(deviceId);
            boolean canRun = true;
            long cnt = 0;
            while (canRun) {
                if(blockDeallocator.get()) {
                    Thread.sleep(1000);
                }

                // if periodicGc is enabled, only first thread will call for it


                if (Nd4j.getMemoryManager().isPeriodicGcActive() && threadIdx == 0 && Nd4j.getMemoryManager().getAutoGcWindow() > 0) {
                    val reference = (DeallocatableReference) queue.poll();
                    if (reference == null || (reference != null && reference.get() != null &&  !reference.get().shouldDeAllocate()) || !reference.getDeallocator().isConstant()) {
                        val timeout = Nd4j.getMemoryManager().getAutoGcWindow();
                        try {
                            Thread.sleep(Nd4j.getMemoryManager().getAutoGcWindow());
                            Nd4j.getMemoryManager().invokeGc();
                        } catch (InterruptedException e) {
                            canRun = false;
                        }
                    } else {
                        // invoking deallocator
                        if (reference != null && (reference.get().shouldDeAllocate()) || reference.getDeallocator().isConstant()) {
                            reference.deallocate();
                            referenceMap.remove(reference.getId());
                        } else {
                            referenceMap.remove(reference.getId());
                        }
                    }
                } else {
                    try {
                        val reference = (DeallocatableReference) queue.remove();
                        if (reference == null)
                            continue;

                        if( (reference.get() != null && !reference.get().shouldDeAllocate()) || reference.getDeallocator().isConstant())
                            continue;

                        if(deallocatedIds.contains(reference.getId()))
                            throw new IllegalArgumentException("Deallocating already deallocated reference.");

                        // invoking deallocator
                        reference.deallocate();
                        referenceMap.remove(reference.getId());
                        deallocatedIds.add(reference.getId());
                    } catch (InterruptedException e) {
                        canRun = false;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }
}
