package cz.doucko.content.adapter.out.persistence

import cz.doucko.content.application.TaskStore
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.Part
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.SourceRef
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskContent
import cz.doucko.content.domain.TaskFilter
import cz.doucko.content.domain.TaskImage
import cz.doucko.content.domain.Text
import cz.doucko.content.domain.Translation
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

/**
 * Tasks: filterable columns plus JSONB payloads (`parts`, `hints`, `translations`) mapped to the typed
 * domain models. `prompt_folded` is the case- and diacritics-folded prompt for the search (CA-D19),
 * matched with `strpos`, so `%` and `_` in a query are literal.
 */
@Repository
class JdbcTaskStore(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : TaskStore {
    override fun list(filter: TaskFilter): List<Task> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()

        fun add(
            clause: String,
            value: Any?,
        ) {
            if (value != null) {
                where += clause
                params += value
            }
        }
        add("grade = ?", filter.grade)
        add("topic_id = ?", filter.topicId)
        add("source_id = ?", filter.sourceId)
        add("page = ?", filter.page)
        add("modality = ?", filter.modality?.name)
        add("status = ?", filter.status?.name)
        add("strpos(prompt_folded, ?) > 0", filter.query?.let(Text::fold))
        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val rows =
            jdbc
                .sql("$SELECT$clause ORDER BY page, task_no, created_at")
                .params(params)
                .query(::row)
                .list()
        val images = images(rows.map { it.id })
        return rows.map { it.copy(images = images[it.id].orEmpty()) }
    }

    override fun find(id: UUID): Task? {
        val task =
            jdbc
                .sql("$SELECT WHERE id = ?")
                .param(id)
                .query(::row)
                .optional()
                .orElse(null) ?: return null
        return task.copy(images = images(listOf(id))[id].orEmpty())
    }

    override fun prompts(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql("SELECT id, prompt FROM task WHERE id IN (:ids)")
                .param("ids", ids.distinct())
                .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("prompt") }
                .list()
                .toMap()
        }

    override fun imageOwner(imageId: UUID): UUID? =
        jdbc
            .sql("SELECT task_id FROM task_image WHERE id = ?")
            .param(imageId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    override fun lock(id: UUID) {
        jdbc
            .sql("SELECT id FROM task WHERE id = ? FOR UPDATE")
            .param(id)
            .query(UUID::class.java)
            .list()
    }

    override fun insert(task: Task) {
        val c = task.content
        jdbc
            .sql(
                """
                INSERT INTO task (id, prompt, prompt_folded, topic_id, grade, difficulty, modality, status, source_id, page,
                                  task_no, sub_part, parts, hints, translations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """.trimIndent(),
            ).params(
                listOf(
                    task.id,
                    c.prompt,
                    Text.fold(c.prompt),
                    c.topicId,
                    c.grade,
                    c.difficulty,
                    c.modality.name,
                    task.status.name,
                    c.sourceRef.sourceId,
                    c.sourceRef.page,
                    c.sourceRef.taskNo,
                    c.sourceRef.subPart,
                    json.writeValueAsString(c.parts),
                    json.writeValueAsString(c.hints),
                    json.writeValueAsString(c.translations),
                ),
            ).update()
    }

    override fun update(task: Task) {
        val c = task.content
        jdbc
            .sql(
                """
                UPDATE task SET prompt = ?, prompt_folded = ?, topic_id = ?, grade = ?, difficulty = ?, modality = ?, source_id = ?,
                                page = ?, task_no = ?, sub_part = ?, parts = ?::jsonb, hints = ?::jsonb, translations = ?::jsonb,
                                updated_at = now()
                WHERE id = ?
                """.trimIndent(),
            ).params(
                listOf(
                    c.prompt,
                    Text.fold(c.prompt),
                    c.topicId,
                    c.grade,
                    c.difficulty,
                    c.modality.name,
                    c.sourceRef.sourceId,
                    c.sourceRef.page,
                    c.sourceRef.taskNo,
                    c.sourceRef.subPart,
                    json.writeValueAsString(c.parts),
                    json.writeValueAsString(c.hints),
                    json.writeValueAsString(c.translations),
                    task.id,
                ),
            ).update()
    }

    override fun setStatus(
        id: UUID,
        status: PublishStatus,
    ) {
        jdbc
            .sql("UPDATE task SET status = ?, updated_at = now() WHERE id = ?")
            .params(status.name, id)
            .update()
    }

    override fun delete(id: UUID) {
        jdbc.sql("DELETE FROM task WHERE id = ?").param(id).update()
    }

    override fun addImage(
        taskId: UUID,
        image: TaskImage,
    ) {
        jdbc
            .sql("INSERT INTO task_image (id, task_id, position, media_type, storage_key) VALUES (?, ?, ?, ?, ?)")
            .params(image.id, taskId, image.position, image.mediaType, image.storageKey)
            .update()
    }

    override fun removeImage(
        taskId: UUID,
        imageId: UUID,
    ) {
        jdbc
            .sql("DELETE FROM task_image WHERE id = ? AND task_id = ?")
            .params(imageId, taskId)
            .update()
        renumber(taskId, images(listOf(taskId))[taskId].orEmpty().map { it.id })
    }

    override fun reorderImages(
        taskId: UUID,
        imageIds: List<UUID>,
    ) = renumber(taskId, imageIds)

    /** The (task_id, position) unique constraint is deferred to the commit, so positions may swap here. */
    private fun renumber(
        taskId: UUID,
        imageIds: List<UUID>,
    ) {
        imageIds.forEachIndexed { index, id ->
            jdbc
                .sql("UPDATE task_image SET position = ? WHERE id = ? AND task_id = ?")
                .params(index + 1, id, taskId)
                .update()
        }
    }

    private fun images(taskIds: List<UUID>): Map<UUID, List<TaskImage>> {
        if (taskIds.isEmpty()) return emptyMap()
        return jdbc
            .sql("SELECT id, task_id, position, media_type, storage_key FROM task_image WHERE task_id = ANY(?) ORDER BY position")
            .param(taskIds.toTypedArray())
            .query { rs, _ ->
                rs.getObject("task_id", UUID::class.java) to
                    TaskImage(
                        rs.getObject("id", UUID::class.java),
                        rs.getInt("position"),
                        rs.getString("media_type"),
                        rs.getString("storage_key"),
                    )
            }.list()
            .groupBy({ it.first }, { it.second })
    }

    @Suppress("UNUSED_PARAMETER")
    private fun row(
        rs: ResultSet,
        row: Int,
    ) = Task(
        id = rs.getObject("id", UUID::class.java),
        content =
            TaskContent(
                prompt = rs.getString("prompt"),
                topicId = rs.getObject("topic_id", UUID::class.java),
                grade = rs.getInt("grade"),
                difficulty = rs.getInt("difficulty"),
                modality = Modality.valueOf(rs.getString("modality")),
                sourceRef =
                    SourceRef(
                        rs.getObject("source_id", UUID::class.java),
                        rs.getInt("page"),
                        rs.getString("task_no"),
                        rs.getString("sub_part"),
                    ),
                parts = json.readValue(rs.getString("parts"), PARTS),
                hints = json.readValue(rs.getString("hints"), HINTS),
                translations = json.readValue(rs.getString("translations"), TRANSLATIONS),
            ),
        status = PublishStatus.valueOf(rs.getString("status")),
        images = emptyList(),
    )

    private companion object {
        const val SELECT =
            "SELECT id, prompt, topic_id, grade, difficulty, modality, status, source_id, page, task_no, sub_part, parts, hints, " +
                "translations FROM task"
        val PARTS = object : TypeReference<List<Part>>() {}
        val HINTS = object : TypeReference<List<String>>() {}
        val TRANSLATIONS = object : TypeReference<Map<String, Translation>>() {}
    }
}
