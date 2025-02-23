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
import kotlin.test.assertNotNull

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
        assertTrue(model.interfaces.size == 6)
        assertTrue(model.typeDefs.size == 2)
        assertTrue(model.namespaces.size == 1)
        assertTrue(model.namespaces[0].constantes.size == 2)


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
        assertTrue("readOnlyAttribute", model.interfaces[anInterfaceIndex].attributes[1].isReadonly)
        assertTrue(model.interfaces[anInterfaceIndex].hasDecorator(IdlDecorator.NO_DELETE))
        assertEquals("someNamespace::", model.interfaces[anInterfaceIndex].getDecoratorValue("Prefix", ""))

        listOf(
            Triple("unsigned long long", null, "someAttribute"),
            Triple("unsigned long long", null, "readOnlyAttribute"),
            Triple("sequence", listOf("any"), "someSequence"),
            Triple("record", listOf("any", "any"), "someRecord"),
            Triple("FrozenArray", listOf("any"), "someFrozenArray"),
            Triple("Promise", listOf("any"), "somePromise"),
            Triple("Promise", listOf("any"), "somePromiseWithExtraSpace")
        ).forEachIndexed { index, (type, parameterType, name) ->
            assertEquals(type, model.interfaces[anInterfaceIndex].attributes[index].type.typeName)
            assertEquals(parameterType, model.interfaces[anInterfaceIndex].attributes[index].type.parameterTypes)
            assertEquals(name, model.interfaces[anInterfaceIndex].attributes[index].name)
        }


        val anotherInterfaceIndex = 2
        assertTrue(model.interfaces[anotherInterfaceIndex].superInterfaces.contains(model.interfaces[objectBaseIndex].name))
        assertEquals("someNamespaceWithSpace::", model.interfaces[anotherInterfaceIndex].getDecoratorValue("Prefix", ""))

        val javaErrorCallbackIndex = 4
        assertEquals("JavaErrorCallback", model.interfaces[javaErrorCallbackIndex].name)
        assertEquals("ErrorCallback", model.interfaces[javaErrorCallbackIndex].getDecoratorValue("JSImplementation", ""))

        val setLikeIndex = 5
        assertEquals("SetLikeInterface", model.interfaces[setLikeIndex].name)
        assertNotNull(model.interfaces[setLikeIndex].setLike)
        assertEquals("DOMString", model.interfaces[setLikeIndex].setLike?.type?.typeName)

        assertEquals("ATypeDef", model.typeDefs[0].name)
        assertEquals("unsigned long", model.typeDefs[0].type.typeName)
        assertEquals("Value", model.typeDefs[0].decorators[0].key)
        assertEquals("AnotherValue", model.typeDefs[0].decorators[1].key)

        assertEquals("ATypeDef2", model.typeDefs[1].name)
        assertEquals("sequence", model.typeDefs[1].type.typeName)
        assertEquals("DOMString", model.typeDefs[1].type.parameterTypes!![0])

        assertEquals("TypeDefs", model.namespaces[0].name)
        assertEquals("CONST_1", model.namespaces[0].constantes[0].name)
        assertEquals("ATypeDef", model.namespaces[0].constantes[0].type.typeName)
        assertEquals("0x0001", model.namespaces[0].constantes[0].defaultValue)
        assertEquals("CONST_2", model.namespaces[0].constantes[1].name)
        assertEquals("ATypeDef", model.namespaces[0].constantes[1].type.typeName)
        assertEquals("0x0002", model.namespaces[0].constantes[1].defaultValue)
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

    @Test(expected = ParserException::class)
    fun parserTestBadSetLike() {
        val inStream = ParserTest::class.java.classLoader.getResourceAsStream("bad-setlike.idl")!!
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