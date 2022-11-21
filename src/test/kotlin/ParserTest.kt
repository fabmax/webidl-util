import de.fabmax.webidl.generator.jni.java.JniJavaGenerator
import de.fabmax.webidl.generator.jni.nat.JniNativeGenerator
import de.fabmax.webidl.generator.ktjs.KtJsInterfaceGenerator
import de.fabmax.webidl.model.IdlDecorator
import de.fabmax.webidl.parser.ParserException
import de.fabmax.webidl.parser.WebIdlParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class ParserTest {

    @Test
    fun parserTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!

        val model = runBlocking {
            val parser = WebIdlParser(explodeOptionalFunctionParams = false)
            parser.parseStream(inStream)
            parser.finish()
        }

        Assert.assertTrue(model.interfaces.size == 4)

        Assert.assertEquals("AnInterface", model.interfaces[0].name)
        Assert.assertTrue(model.interfaces[0].functions.size == 1)
        Assert.assertEquals("aFunction", model.interfaces[0].functions[0].name)
        Assert.assertTrue(model.interfaces[0].attributes.size == 1)
        Assert.assertEquals("someAttribute", model.interfaces[0].attributes[0].name)
        Assert.assertTrue(model.interfaces[0].hasDecorator(IdlDecorator.NO_DELETE))
        Assert.assertEquals("someNamespace::", model.interfaces[0].getDecoratorValue("Prefix", ""))

        Assert.assertEquals("someNamespaceWithSpace::", model.interfaces[1].getDecoratorValue("Prefix", ""))

        Assert.assertEquals("JavaErrorCallback", model.interfaces[3].name)
        Assert.assertEquals("ErrorCallback", model.interfaces[3].getDecoratorValue("JSImplementation", ""))
    }

    @Test(expected = ParserException::class)
    fun parserTestNoReturnType() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("bad-ctor.idl")!!
        WebIdlParser.parseFromInputStream(inStream)
    }

    @Test(expected = ParserException::class)
    fun parserTestBadEnum() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("bad-enum.idl")!!
        WebIdlParser.parseFromInputStream(inStream)
    }

    @Test
    fun generatorJsTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!
        val model = WebIdlParser.parseFromInputStream(inStream)

        KtJsInterfaceGenerator().apply {
            outputDirectory = "test_output/js"
        }.generate(model)
    }

    @Test
    fun generatorJniNativeTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!
        val model = WebIdlParser.parseFromInputStream(inStream)

        JniNativeGenerator().apply {
            outputDirectory = "test_output/jni_native"
        }.generate(model)
    }

    @Test
    fun generatorJniJavaTest() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("test.idl")!!
        val model = WebIdlParser.parseFromInputStream(inStream)

        JniJavaGenerator().apply {
            outputDirectory = "test_output/jni_java"
        }.generate(model)
    }
}