package com.lightningstudio.watchrss.phone.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipCatalogTest {

    @Test
    fun `tip ids are unique`() {
        val ids = TipCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all event references are declared in TipEvents`() {
        val knownEvents = TipEvents::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
        for (tip in TipCatalog.all) {
            val referenced = buildSet {
                addAll(tip.invalidateOnEvents)
                collectEventKeys(tip.rule, this)
            }
            assertTrue("tip ${tip.id} references undeclared events: $referenced", knownEvents.containsAll(referenced))
        }
    }

    @Test
    fun `all parameter references are declared in TipParameters`() {
        val knownParameters = TipParameters::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
        for (tip in TipCatalog.all) {
            val referenced = buildSet {
                collectParameterKeys(tip.rule, this)
            }
            assertTrue(
                "tip ${tip.id} references undeclared parameters: $referenced",
                knownParameters.containsAll(referenced)
            )
        }
    }

    @Test
    fun `copy is non blank and concise`() {
        for (tip in TipCatalog.all) {
            assertTrue("tip ${tip.id} has blank title", tip.title.isNotBlank())
            assertTrue("tip ${tip.id} has blank message", tip.message.isNotBlank())
            assertTrue("tip ${tip.id} title must be one line", !tip.title.contains("\n"))
            assertTrue("tip ${tip.id} message must fit two lines", tip.message.lines().size <= 2)
        }
    }

    @Test
    fun `unknown id resolves to null`() {
        assertNull(TipCatalog.byId("__never_exists__"))
    }

    private fun collectEventKeys(rule: TipRule?, into: MutableSet<String>) {
        when (rule) {
            is TipRule.EventCount -> into.add(rule.event)
            is TipRule.AllOf -> rule.rules.forEach { collectEventKeys(it, into) }
            is TipRule.AnyOf -> rule.rules.forEach { collectEventKeys(it, into) }
            else -> Unit
        }
    }

    private fun collectParameterKeys(rule: TipRule?, into: MutableSet<String>) {
        when (rule) {
            is TipRule.ParamBoolean -> into.add(rule.key)
            is TipRule.ParamEquals -> into.add(rule.key)
            is TipRule.AllOf -> rule.rules.forEach { collectParameterKeys(it, into) }
            is TipRule.AnyOf -> rule.rules.forEach { collectParameterKeys(it, into) }
            else -> Unit
        }
    }
}
