package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider

/**
 * An in-memory database opened at a pre-migration schema version, so one migration can be run
 * against rows it has to carry across. [createSchema] writes the *before* schema by hand — only
 * the tables the migration touches — rather than driving Room's MigrationTestHelper, which would
 * need the exported schema JSON this project doesn't keep.
 *
 * `onUpgrade` is deliberately a no-op: the test calls the migration itself, so it sees the same
 * DDL Room would run and can assert on the result. Closed by the test's `@After`.
 */
class MigrationDb(version: Int, createSchema: (SupportSQLiteDatabase) -> Unit) {

    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext<Context>())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = createSchema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build(),
    )

    val db: SupportSQLiteDatabase = helper.writableDatabase

    fun close() = helper.close()
}
