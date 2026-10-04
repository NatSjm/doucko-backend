package cz.doucko.content.adapter.out.persistence

import cz.doucko.content.application.SourceStore
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.SourceMetadata
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** PDF sources (FR-1). The file itself lives in the file storage under `storage_key`. */
@Repository
class JdbcSourceStore(
    private val jdbc: JdbcClient,
) : SourceStore {
    override fun list(): List<Source> = jdbc.sql("$SELECT ORDER BY title, created_at").query(::source).list()

    override fun find(id: UUID): Source? =
        jdbc
            .sql("$SELECT WHERE id = ?")
            .param(id)
            .query(::source)
            .optional()
            .orElse(null)

    override fun insert(source: Source) {
        val m = source.metadata
        jdbc
            .sql(
                "INSERT INTO source (id, title, publisher, edition, grade, subject, page_count, storage_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).params(listOf(source.id, m.title, m.publisher, m.edition, m.grade, m.subject, source.pageCount, source.storageKey))
            .update()
    }

    override fun update(source: Source) {
        val m = source.metadata
        jdbc
            .sql("UPDATE source SET title = ?, publisher = ?, edition = ?, grade = ?, subject = ? WHERE id = ?")
            .params(listOf(m.title, m.publisher, m.edition, m.grade, m.subject, source.id))
            .update()
    }

    override fun delete(id: UUID) {
        jdbc.sql("DELETE FROM source WHERE id = ?").param(id).update()
    }

    override fun referencingTasks(id: UUID): List<UUID> =
        jdbc
            .sql("SELECT id FROM task WHERE source_id = ? ORDER BY created_at")
            .param(id)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    @Suppress("UNUSED_PARAMETER")
    private fun source(
        rs: ResultSet,
        row: Int,
    ) = Source(
        rs.getObject("id", UUID::class.java),
        SourceMetadata(
            rs.getString("title"),
            rs.getString("publisher"),
            rs.getString("edition"),
            rs.getInt("grade"),
            rs.getString("subject"),
        ),
        rs.getInt("page_count"),
        rs.getString("storage_key"),
    )

    private companion object {
        const val SELECT = "SELECT id, title, publisher, edition, grade, subject, page_count, storage_key FROM source"
    }
}
