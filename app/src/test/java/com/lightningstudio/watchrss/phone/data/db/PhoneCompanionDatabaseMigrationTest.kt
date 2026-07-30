package com.lightningstudio.watchrss.phone.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

class PhoneCompanionDatabaseMigrationTest {
    @Test
    fun migration11To12_onlyAddsReadingPositionColumnsWithDefaults() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)

        PhoneCompanionDatabase.MIGRATION_11_12.migrate(database)

        assertEquals(3, statements.size)
        assertEquals(
            setOf(
                "readingPositionBytes INTEGER NOT NULL DEFAULT 0",
                "readingPositionContentHash TEXT NOT NULL DEFAULT ''",
                "readingPositionChangedAt INTEGER NOT NULL DEFAULT 0"
            ),
            statements.map { it.substringAfter("ADD COLUMN ") }.toSet()
        )
        assertFalse(statements.any { it.contains("DROP ", ignoreCase = true) })
        assertFalse(statements.any { it.contains("DELETE ", ignoreCase = true) })
    }

    private fun recordingDatabase(statements: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, arguments ->
            if (method.name == "execSQL") {
                statements += arguments.orEmpty().first() as String
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
}
