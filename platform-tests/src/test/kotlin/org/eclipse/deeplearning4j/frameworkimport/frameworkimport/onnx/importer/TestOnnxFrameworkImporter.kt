package org.eclipse.deeplearning4j.frameworkimport.frameworkimport.onnx.importer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.nd4j.autodiff.samediff.TrainingConfig
import org.nd4j.common.io.ClassPathResource
import org.nd4j.common.resources.Resources
import org.nd4j.common.tests.tags.TagNames
import org.nd4j.linalg.api.buffer.DataType
import org.nd4j.linalg.dataset.DataSet
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.learning.config.Adam
import org.nd4j.onnxruntime.runner.OnnxRuntimeRunner
import org.nd4j.onnxruntime.util.ONNXUtils
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter
import org.nd4j.samediff.frameworkimport.onnx.ir.OnnxIRTensor
import java.io.File
import java.util.*

@Tag(TagNames.ONNX)
class TestOnnxFrameworkImporter {



    @Test
    fun testGru() {
        val importer = OnnxFrameworkImporter()
        val file = File("/home/agibsonccc/Documents/GitHub/deeplearning4j/Single_GRU.onnx")
        val onnxRunner = OnnxRuntimeRunner(file.absolutePath)
        val inputs = mapOf("input" to ONNXUtils.getSampleForValueInfo(onnxRunner.inputs[0]),
            "hidden" to ONNXUtils.getSampleForValueInfo(onnxRunner.inputs[1]))
        val runnerOutput = onnxRunner.exec(inputs)
        //tests model import with constant initializers where an output of a constant node is
        //defined
        val output = importer.runImport(file.absolutePath, suggestDynamicVariables = true)
        //ensure that the graph with an eager mode can automatically import the model
        println(output.summary())
        assertNotNull(output)
        val output2 = output.outputAll(inputs)
        println(output2)

    }

    @Test
    fun testConstantInitialization() {
        val importer = OnnxFrameworkImporter()
        val file = Resources.asFile("onnx_graphs/output_cnn_mnist.onnx")
        //tests model import with constant initializers where an output of a constant node is
        //defined
        val output = importer.runImport(file.absolutePath, suggestDynamicVariables = true)
        //ensure that the graph with an eager mode can automatically import the model
        assertNotNull(output)
    }


    @Test
    fun testSuggestedVariables() {
        val importer = OnnxFrameworkImporter()
        val file = ClassPathResource("mobilenet.onnx").file
        val suggestedVariables = importer.suggestDynamicVariables(file.absolutePath)
        assertTrue(suggestedVariables.containsKey("input.1"))
        val shape = suggestedVariables["input.1"]!!.shape()
        assertArrayEquals(longArrayOf(1,3,224,224),shape)

    }

    @Test
    fun testMobileNet() {
        Nd4j.getExecutioner().enableVerboseMode(true)
        Nd4j.getExecutioner().enableDebugMode(true)
        val importer = OnnxFrameworkImporter()
        val file = ClassPathResource("mobilenet.onnx").file
        val result  = importer.runImport(file.absolutePath, emptyMap(),suggestDynamicVariables = true)
        result.outputAll(Collections.singletonMap("input.1",Nd4j.ones(1,3,224,224)))
    }




}