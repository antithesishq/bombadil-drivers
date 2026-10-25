package me.uzaaft.bombadil.android

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenTest {
    private val defaults = """index="0" text="" resource-id="" class="android.view.View" package="app" """ +
        """content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" """ +
        """focused="false" scrollable="false" long-clickable="false" password="false" selected="false""""

    private fun attributes(text: String) =
        Regex("""([\w-]+)="([^"]*)"""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    private fun node(overrides: String, children: String = ""): String {
        val all = attributes(defaults) + attributes(overrides)
        return "<node ${all.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" }}>$children</node>"
    }

    // What `uiautomator dump /dev/tty; echo; dumpsys window | grep ...` prints, trimmed.
    private val output =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">" +
            node(
                """class="android.widget.FrameLayout" bounds="[0,0][1080,2400]"""",
                node(
                    """resource-id="niaTopAppBar" bounds="[0,0][1080,296]"""",
                    node(
                        """clickable="true" bounds="[12,150][138,276]"""",
                        node("""content-desc="Search" bounds="[43,181][106,244]""""),
                    ),
                ) +
                    node(
                        """clickable="true" checked="true" bounds="[63,637][882,784]"""",
                        node("""text="Headlines" class="android.widget.TextView" bounds="[263,689][703,732]"""") +
                            node("""text="Gone" bounds="[0,0][0,0]""""),
                    ),
            ) +
            "</hierarchy>UI hierchary dumped to: /dev/tty\n\n" +
            "  mCurrentFocus=Window{7f68b6d u0 app/app.MainActivity}\n" +
            "  mFocusedApp=ActivityRecord{3ac2664 u0 app/.MainActivity t8}\n"

    @Test
    fun parsesTheFocusedAppAndPrunesLayoutOnlyNodes() {
        val screen = Screen.parse(output)
        assertEquals("app", screen.packageName)
        assertEquals("app.MainActivity", screen.activity)
        assertEquals("app/app.MainActivity", screen.focus)
        // The root FrameLayout carries nothing and is dropped; so is the empty `Gone` node.
        val (bar, row) = screen.nodes
        assertEquals("niaTopAppBar", bar.resourceId)
        assertEquals(listOf("Search"), bar.flatten().map { it.label }.drop(1).toList().distinct())
        assertEquals("Headlines", row.label)
        assertEquals(true, row.checked)
        assertEquals(listOf("Headlines"), row.children.map { it.text })
        assertEquals(Bounds(63, 637, 882, 784), row.bounds)
    }

    @Test
    fun quotesTextForInputText() {
        assertEquals("""'it'\''s%sok'""", inputTextArgument("it's ok"))
        assertEquals("'ab'", inputTextArgument("a\u00e9b"))
    }
}
