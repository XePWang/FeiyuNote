package com.feiyu.notes.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillImportTest {
    private val raw = "https://raw.githubusercontent.com/"

    @Test fun githubLinksMapToRawSkillMd() {
        assertEquals(raw + "o/r/HEAD/SKILL.md", SkillImport.rawUrl("https://github.com/o/r"))
        assertEquals(raw + "o/r/main/skills/tutor/SKILL.md", SkillImport.rawUrl("https://github.com/o/r/tree/main/skills/tutor"))
        assertEquals(raw + "o/r/main/skills/tutor/SKILL.md", SkillImport.rawUrl("https://github.com/o/r/blob/main/skills/tutor/SKILL.md"))
        assertEquals(raw + "o/r/main/x/SKILL.md", SkillImport.rawUrl(" ${raw}o/r/main/x/SKILL.md "))
    }

    @Test fun otherHostsSchemesAndShapesAreRejected() {
        listOf("http://github.com/o/r", "https://example.com/o/r/SKILL.md", "https://github.com/o",
            "https://github.com/o/r/issues/1", "https://github.com/o/r?x=1", "not a url").forEach { assertNull(it, SkillImport.rawUrl(it)) }
    }

    @Test fun frontMatterGivesNameAndBodyBecomesInstruction() {
        val skill = SkillImport.parse("---\r\nname: \"Socratic tutor\"\r\ndescription: Ask before telling\r\n---\r\n\r\nAsk one question at a time.\r\n", raw + "o/r/main/socratic/SKILL.md")
        assertEquals("Socratic tutor", skill.name)
        assertEquals("Ask before telling", skill.description)
        assertEquals("Ask one question at a time.", skill.instruction)
        assertEquals("socratic", SkillImport.parse("Plain body", raw + "o/r/main/socratic/SKILL.md").name)
        assertThrows(SkillImport.SkillException::class.java) { SkillImport.parse("---\nname: x\n---\n  \n", raw + "o/r/m/SKILL.md") }
    }
}
