import de.fabmax.webidl.generator.jni.java.JniJavaGenerator
import de.fabmax.webidl.generator.jni.nat.JniNativeGenerator
import de.fabmax.webidl.generator.ktjs.KtJsInterfaceGenerator
import de.fabmax.webidl.model.IdlDecorator
import de.fabmax.webidl.parser.ParserException
import de.fabmax.webidl.parser.WebIdlParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertTrue(model.dictionaries.size == 1)
        assertTrue(model.interfaces.size == 5)


        assertEquals("AnDictionary", model.dictionaries[0].name)
        assertEquals("someMember", model.dictionaries[0].members[0].name)
        assertTrue("requiredMember", model.dictionaries[0].members[1].isRequired)
        assertEquals("someMemberWithDefaultValue", model.dictionaries[0].members[2].name)
        assertEquals("\"my string\"", model.dictionaries[0].members[2].defaultValue)

        val objectBaseIndex = 0
        assertEquals("ObjectBase", model.interfaces[objectBaseIndex].name)
        assertEquals("label", model.interfaces[objectBaseIndex].attributes[0].name)
        assertTrue(model.interfaces[objectBaseIndex].isMixin)

        val anInterfaceIndex = 1
        assertEquals("AnInterface", model.interfaces[anInterfaceIndex].name)
        assertTrue(model.interfaces[anInterfaceIndex].functions.size == 1)
        assertEquals("aFunction", model.interfaces[anInterfaceIndex].functions[0].name)
        assertTrue(model.interfaces[anInterfaceIndex].attributes.size == 7)
        assertEquals("someAttribute", model.interfaces[anInterfaceIndex].attributes[0].name)
        assertTrue("readOnlyAttribute", model.interfaces[anInterfaceIndex].attributes[1].isReadonly)
        assertTrue(model.interfaces[anInterfaceIndex].hasDecorator(IdlDecorator.NO_DELETE))
        assertEquals("someNamespace::", model.interfaces[anInterfaceIndex].getDecoratorValue("Prefix", ""))

        val anotherInterfaceIndex = 2
        assertTrue(model.interfaces[anotherInterfaceIndex].superInterfaces.contains(model.interfaces[objectBaseIndex].name))
        assertEquals("someNamespaceWithSpace::", model.interfaces[anotherInterfaceIndex].getDecoratorValue("Prefix", ""))

        val javaErrorCallbackIndex = 4
        assertEquals("JavaErrorCallback", model.interfaces[javaErrorCallbackIndex].name)
        assertEquals("ErrorCallback", model.interfaces[javaErrorCallbackIndex].getDecoratorValue("JSImplementation", ""))

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