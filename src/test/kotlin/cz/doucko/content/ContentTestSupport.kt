package cz.doucko.content

import cz.doucko.admin.Admin
import cz.doucko.content.ContentUsage
import cz.doucko.content.adapter.`in`.web.ContentProblems
import cz.doucko.content.adapter.out.pdf.PdfBoxInspector
import cz.doucko.content.application.Conflicts
import cz.doucko.content.application.ContentConfiguration
import cz.doucko.content.application.ContentLock
import cz.doucko.content.application.FileStoragePort
import cz.doucko.content.application.MissionService
import cz.doucko.content.application.MissionStore
import cz.doucko.content.application.ReferenceService
import cz.doucko.content.application.ServedContentService
import cz.doucko.content.application.SourceService
import cz.doucko.content.application.SourceStore
import cz.doucko.content.application.TaskService
import cz.doucko.content.application.TaskStore
import cz.doucko.content.application.TopicStore
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskFilter
import cz.doucko.content.domain.TaskImage
import cz.doucko.content.domain.Text
import cz.doucko.content.domain.Topic
import jakarta.servlet.http.Cookie
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMultipartHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Seeded grade-6 topics of V2 (self-authored), and a grade-7 topic that only tests add. */
object Topics {
    val NATURAL = UUID.fromString("6a000000-0000-4000-8000-000000000001")
    val DECIMALS = UUID.fromString("6a000000-0000-4000-8000-000000000002")
    val ANGLES = UUID.fromString("6a000000-0000-4000-8000-000000000004")
    val GRADE7 = UUID.fromString("7a000000-0000-4000-8000-000000000001")
    val seeded =
        listOf(
            Topic(NATURAL, "g6-natural-numbers", 6, "Přirozená čísla"),
            Topic(DECIMALS, "g6-decimals", 6, "Desetinná čísla"),
            Topic(ANGLES, "g6-angles", 6, "Úhly"),
        )
    val grade7 = Topic(GRADE7, "g7-test", 7, "Testovací téma 7")
}

/**
 * Self-authored test files, generated at run time (NFR-4: nothing from a workbook, no committed binary).
 */
object Files {
    fun pdf(pages: Int): ByteArray =
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    fun encryptedPdf(): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.protect(StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission()).apply { encryptionKeyLength = 128 })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    fun truncatedPdf(): ByteArray = pdf(3).let { it.copyOf(it.size / 2) }

    /** A PDF whose catalog's `/Pages` points to an object that is not a page tree. */
    fun brokenPageTreePdf(): ByteArray {
        val body =
            "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n(not a page tree)\nendobj\n"
        val xref = body.length
        val text =
            body +
                "xref\n0 3\n0000000000 65535 f \n" +
                "%010d 00000 n \n".format(9) +
                "%010d 00000 n \n".format(body.indexOf("2 0 obj")) +
                "trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
        return text.toByteArray(Charsets.US_ASCII)
    }

    fun png(
        width: Int = 4,
        height: Int = 3,
    ): ByteArray = image("png", width, height)

    fun jpg(): ByteArray = image("jpg", 4, 3)

    fun gif(): ByteArray = image("gif", 4, 3)

    /** A JPG padded past [size] bytes (the type check reads the signature only). */
    fun jpgOfSize(size: Int): ByteArray = jpg().copyOf(size)

    /** A ZIP container like a `.docx`. */
    fun docx(): ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64)

    private fun image(
        format: String,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) }.toByteArray()
    }
}

class InMemoryTopicStore : TopicStore {
    val topics = (Topics.seeded + Topics.grade7).toMutableList()

    override fun all() = topics.sortedBy { it.grade }

    override fun find(id: UUID) = topics.firstOrNull { it.id == id }
}

class InMemorySourceStore(
    private val tasks: InMemoryTaskStore,
) : SourceStore {
    val sources = linkedMapOf<UUID, Source>()

    override fun list() = sources.values.sortedBy { it.metadata.title }

    override fun find(id: UUID) = sources[id]

    override fun insert(source: Source) {
        sources[source.id] = source
    }

    override fun update(source: Source) {
        sources[source.id] = source
    }

    override fun delete(id: UUID) {
        sources.remove(id)
    }

    override fun referencingTasks(id: UUID) =
        tasks.tasks.values
            .filter { it.content.sourceRef.sourceId == id }
            .map { it.id }
}

class InMemoryTaskStore : TaskStore {
    val tasks = linkedMapOf<UUID, Task>()

    /** Fault injection: the next insert fails as a concurrent database rule violation would. */
    var failNextInsert = false

    override fun list(filter: TaskFilter) =
        tasks.values.filter { t ->
            val c = t.content
            (filter.grade == null || c.grade == filter.grade) &&
                (filter.topicId == null || c.topicId == filter.topicId) &&
                (filter.sourceId == null || c.sourceRef.sourceId == filter.sourceId) &&
                (filter.page == null || c.sourceRef.page == filter.page) &&
                (filter.modality == null || c.modality == filter.modality) &&
                (filter.status == null || t.status == filter.status) &&
                (filter.query == null || Text.fold(c.prompt).contains(Text.fold(filter.query!!)))
        }

    override fun find(id: UUID) = tasks[id]

    override fun lock(id: UUID) = Unit

    override fun insert(task: Task) {
        if (failNextInsert) {
            failNextInsert = false
            throw org.springframework.dao.DataIntegrityViolationException("fk")
        }
        tasks[task.id] = task
    }

    override fun update(task: Task) {
        tasks[task.id] = task.copy(status = tasks.getValue(task.id).status, images = tasks.getValue(task.id).images)
    }

    override fun setStatus(
        id: UUID,
        status: PublishStatus,
    ) {
        tasks[id] = tasks.getValue(id).copy(status = status)
    }

    override fun delete(id: UUID) {
        tasks.remove(id)
    }

    override fun addImage(
        taskId: UUID,
        image: TaskImage,
    ) {
        val task = tasks.getValue(taskId)
        tasks[taskId] = task.copy(images = task.images + image)
    }

    override fun removeImage(
        taskId: UUID,
        imageId: UUID,
    ) {
        val task = tasks.getValue(taskId)
        tasks[taskId] = task.copy(images = task.images.filter { it.id != imageId }.mapIndexed { i, it -> it.copy(position = i + 1) })
    }

    override fun reorderImages(
        taskId: UUID,
        imageIds: List<UUID>,
    ) {
        val task = tasks.getValue(taskId)
        tasks[taskId] = task.copy(images = imageIds.mapIndexed { i, id -> task.images.first { it.id == id }.copy(position = i + 1) })
    }
}

/** Missions in memory, ordered like the JDBC store: topic order, then position. */
class InMemoryMissionStore(
    private val topics: InMemoryTopicStore,
) : MissionStore {
    val missions = linkedMapOf<UUID, Mission>()

    private fun order(list: Collection<Mission>) =
        list.sortedWith(compareBy({ m -> topics.all().indexOfFirst { it.id == m.content.topicId } }, { it.content.position }))

    override fun list() = order(missions.values)

    override fun find(id: UUID) = missions[id]

    override fun insert(mission: Mission) {
        missions[mission.id] = mission
    }

    override fun update(mission: Mission) {
        missions[mission.id] = mission.copy(status = missions.getValue(mission.id).status)
    }

    override fun setStatus(
        id: UUID,
        status: PublishStatus,
    ) {
        missions[id] = missions.getValue(id).copy(status = status)
    }

    override fun delete(id: UUID) {
        missions.remove(id)
    }

    override fun containing(taskId: UUID) = order(missions.values.filter { taskId in it.content.taskIds })

    override fun positionsTaken(
        topicId: UUID,
        excluding: UUID?,
    ) = missions.values
        .filter { it.content.topicId == topicId && it.id != excluding }
        .map { it.content.position }
        .toSet()

    override fun removeTask(taskId: UUID) {
        missions.replaceAll { _, m -> m.copy(content = m.content.copy(taskIds = m.content.taskIds - taskId)) }
    }
}

/** Practice usage as a test sets it (the real adapter is slice 7): attempts, reports and opened hints per task. */
class FakeContentUsage : ContentUsage {
    val attempts = mutableMapOf<UUID, Int>()
    val reports = mutableSetOf<UUID>()
    val openedHints = mutableSetOf<UUID>()

    fun clear() {
        attempts.clear()
        reports.clear()
        openedHints.clear()
    }

    override fun attemptCounts(taskIds: Collection<UUID>) = attempts.filterKeys { it in taskIds }

    override fun usedTasks(taskIds: Collection<UUID>) =
        taskIds.filter { (attempts[it] ?: 0) > 0 || it in reports || it in openedHints }.toSet()
}

class InMemoryFileStorage : FileStoragePort {
    val files = ConcurrentHashMap<String, ByteArray>()

    override fun store(
        key: String,
        content: ByteArray,
    ) {
        files[key] = content
    }

    override fun open(key: String): InputStream? = files[key]?.let(::ByteArrayInputStream)

    override fun delete(key: String) {
        files.remove(key)
    }

    override fun locate(key: String): Path = Path.of(System.getProperty("java.io.tmpdir"), "in-memory", key)
}

/** The content use cases over in-memory stores, for `@WebMvcTest` slices (every controller needs them). */
@TestConfiguration(proxyBeanMethods = false)
@Import(
    ContentConfiguration::class,
    SourceService::class,
    TaskService::class,
    MissionService::class,
    ServedContentService::class,
    Conflicts::class,
    ReferenceService::class,
    PdfBoxInspector::class,
    ContentProblems::class,
)
class ContentWebTestBeans {
    @Bean
    fun taskStore() = InMemoryTaskStore()

    @Bean
    fun sourceStore(tasks: InMemoryTaskStore) = InMemorySourceStore(tasks)

    @Bean
    fun topicStore() = InMemoryTopicStore()

    @Bean
    fun fileStorage() = InMemoryFileStorage()

    @Bean
    fun missionStore(topics: InMemoryTopicStore) = InMemoryMissionStore(topics)

    @Bean
    fun contentLock() = ContentLock {}

    @Bean
    fun contentUsage() = FakeContentUsage()
}

private fun MockHttpServletRequestDsl.asAdmin(
    admin: Admin,
    csrf: Boolean = true,
) {
    session = admin.session
    cookie(Cookie("XSRF-TOKEN", admin.xsrf))
    if (csrf) header("X-XSRF-TOKEN", admin.xsrf)
}

private fun MockMultipartHttpServletRequestDsl.asAdmin(admin: Admin) {
    session = admin.session
    cookie(Cookie("XSRF-TOKEN", admin.xsrf))
    header("X-XSRF-TOKEN", admin.xsrf)
}

fun MockMvc.adminGet(
    admin: Admin?,
    path: String,
    vararg params: Pair<String, String>,
) = get(path) {
    admin?.let { session = it.session }
    params.forEach { (k, v) -> param(k, v) }
}

fun MockMvc.adminJson(
    method: String,
    admin: Admin,
    path: String,
    body: String,
    csrf: Boolean = true,
): ResultActionsDsl {
    val dsl: MockHttpServletRequestDsl.() -> Unit = {
        asAdmin(admin, csrf)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
    return when (method) {
        "POST" -> post(path, dsl = dsl)
        "PUT" -> put(path, dsl = dsl)
        else -> error(method)
    }
}

fun MockMvc.adminDelete(
    admin: Admin,
    path: String,
) = delete(path) { asAdmin(admin) }

fun MockMvc.uploadSource(
    admin: Admin,
    file: ByteArray?,
    vararg fields: Pair<String, String>,
    name: String = "sesit.pdf",
) = multipart("/api/admin/sources") {
    asAdmin(admin)
    file?.let { file(MockMultipartFile("file", name, "application/pdf", it)) }
    fields.forEach { (k, v) -> param(k, v) }
}

fun MockMvc.addImage(
    admin: Admin,
    taskId: Any,
    file: ByteArray,
    name: String = "obrazek.png",
) = multipart("/api/admin/tasks/$taskId/images") {
    asAdmin(admin)
    file(MockMultipartFile("file", name, "image/png", file))
}

/** Valid source metadata of the spec scenario. */
val SOURCE_FIELDS =
    arrayOf(
        "title" to "Matematika 6 – pracovní sešit",
        "publisher" to "Testovací nakladatelství",
        "edition" to "1",
        "grade" to "6",
        "subject" to "math",
    )

/** A valid single-part TEXT task body; [overrides] replace top-level JSON members (raw JSON values). */
fun taskJson(
    sourceId: Any,
    vararg overrides: Pair<String, String>,
): String {
    val members =
        linkedMapOf(
            "prompt" to "\"Vypočítej obvod obdélníku.\"",
            "topicId" to "\"${Topics.DECIMALS}\"",
            "grade" to "6",
            "difficulty" to "2",
            "modality" to "\"TEXT\"",
            "sourceRef" to """{"sourceId":"$sourceId","page":57,"taskNo":"4","subPart":"b"}""",
            "parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}}]""",
            "hints" to "[]",
        )
    overrides.forEach { (k, v) -> members[k] = v }
    return members.entries.filter { it.value != "ABSENT" }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
}

fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
