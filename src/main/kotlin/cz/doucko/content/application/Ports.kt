package cz.doucko.content.application

import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskFilter
import cz.doucko.content.domain.TaskImage
import cz.doucko.content.domain.Topic
import java.io.InputStream
import java.nio.file.Path
import java.util.UUID

/**
 * Uploaded files (NFR-17): stored under a root outside every repository working tree. Keys are
 * relative names such as `sources/<id>.pdf`; [locate] gives the absolute path (tests prove where files
 * land).
 */
interface FileStoragePort {
    fun store(
        key: String,
        content: ByteArray,
    )

    fun open(key: String): InputStream?

    fun delete(key: String)

    fun locate(key: String): Path
}

/** Reads a PDF's page count (FR-1). Null when it cannot be read: encrypted, truncated, broken (CA-D2). */
fun interface PdfInspector {
    fun pageCount(content: ByteArray): Int?
}

interface TopicStore {
    fun all(): List<Topic>

    fun find(id: UUID): Topic?
}

interface SourceStore {
    fun list(): List<Source>

    fun find(id: UUID): Source?

    fun insert(source: Source)

    fun update(source: Source)

    fun delete(id: UUID)

    /** Tasks that reference the source (CA-D4, CA-D16). */
    fun referencingTasks(id: UUID): List<UUID>
}

interface TaskStore {
    fun list(filter: TaskFilter): List<Task>

    fun find(id: UUID): Task?

    /** Locks the task row until the transaction ends, so image edits of one task run one at a time. */
    fun lock(id: UUID)

    fun insert(task: Task)

    fun update(task: Task)

    fun setStatus(
        id: UUID,
        status: PublishStatus,
    )

    fun delete(id: UUID)

    fun addImage(
        taskId: UUID,
        image: TaskImage,
    )

    fun removeImage(
        taskId: UUID,
        imageId: UUID,
    )

    /** Rewrites image positions to the order of [imageIds] (every image of the task, each once). */
    fun reorderImages(
        taskId: UUID,
        imageIds: List<UUID>,
    )
}
