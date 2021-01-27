import de.fabmax.webidl.generator.jni.JniJavaGenerator
import de.fabmax.webidl.generator.jni.JniNativeGenerator
import de.fabmax.webidl.generator.js.JsInterfaceGenerator
import de.fabmax.webidl.parser.WebIdlParser
import org.junit.Assert
import org.junit.Test

class ParserTest {

    @Test
    fun parserTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!

        val parser = WebIdlParser()
        parser.explodeOptionalFunctionParams = false
        val model = parser.parse(inStream)

        Assert.assertTrue(model.interfaces.size == 2)

        Assert.assertEquals(model.interfaces[0].name, "AnInterface")
        Assert.assertTrue(model.interfaces[0].functions.size == 1)
        Assert.assertEquals(model.interfaces[0].functions[0].name, "aFunction")
        Assert.assertTrue(model.interfaces[0].attributes.size == 1)
        Assert.assertEquals(model.interfaces[0].attributes[0].name, "someAttribute")
        Assert.assertTrue(model.interfaces[0].hasDecorator("NoDelete"))
        Assert.assertEquals(model.interfaces[0].getDecoratorValue("Prefix", ""), "namespace::")
    }

    @Test
    fun generatorJsTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!
        val model = WebIdlParser().parse(inStream)

        JsInterfaceGenerator().apply {
            outputDirectory = "test_output/js"
        }.generate(model)
    }

    @Test
    fun generatorJniNativeTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!
        val model = WebIdlParser().parse(inStream)

        JniNativeGenerator().apply {
            outputDirectory = "test_output/jni_native"
        }.generate(model)
    }

    @Test
    fun generatorJniJaqvaTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!
        val model = WebIdlParser().parse(inStream)

        JniJavaGenerator().apply {
            outputDirectory = "test_output/jni_java"
        }.generate(model)
    }
}