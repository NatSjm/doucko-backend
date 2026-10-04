package cz.doucko.content.adapter.out.persistence

import cz.doucko.content.application.TopicStore
import cz.doucko.content.domain.Topic
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** Seeded topics (V2), read-only: MVP has no topic editor. */
@Repository
class JdbcTopicStore(
    private val jdbc: JdbcClient,
) : TopicStore {
    override fun all(): List<Topic> = jdbc.sql("SELECT id, code, grade, name FROM topic ORDER BY grade, position").query(::topic).list()

    override fun find(id: UUID): Topic? =
        jdbc
            .sql("SELECT id, code, grade, name FROM topic WHERE id = ?")
            .param(id)
            .query(::topic)
            .optional()
            .orElse(null)

    @Suppress("UNUSED_PARAMETER")
    private fun topic(
        rs: ResultSet,
        row: Int,
    ) = Topic(rs.getObject("id", UUID::class.java), rs.getString("code"), rs.getInt("grade"), rs.getString("name"))
}
