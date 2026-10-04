package cz.doucko.content.adapter.out.files

import cz.doucko.content.application.ContentProperties
import cz.doucko.content.application.FileStoragePort
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Uploaded files on the local storage volume (NFR-17, NFR-4): `doucko.content.storage-dir`
 * (`DOUCKO_STORAGE_DIR`). The root must not lie inside a git working tree ([StorageRoot]), so a PDF
 * or image can never end up in a repository. Files are written under a temporary name and moved into
 * place, so a reader never sees half a file.
 */
@Component
class FilesystemFileStorage(
    properties: ContentProperties,
) : FileStoragePort {
    val root: Path = StorageRoot.check(properties.storageDir)

    override fun store(
        key: String,
        content: ByteArray,
    ) {
        val target = locate(key)
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, content)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun open(key: String): InputStream? = locate(key).takeIf(Files::isRegularFile)?.let(Files::newInputStream)

    override fun delete(key: String) {
        Files.deleteIfExists(locate(key))
    }

    override fun locate(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root) && path != root) { "storage key outside the storage root" }
        return path
    }
}

/** The storage-root rule (NFR-17): set, absolute after resolution, and outside every git working tree. */
object StorageRoot {
    const val SETTING = "doucko.content.storage-dir (DOUCKO_STORAGE_DIR)"

    fun check(configured: String): Path {
        check(configured.isNotBlank()) { "$SETTING is not set." }
        val root = Path.of(configured).toAbsolutePath().normalize()
        val repository = generateSequence(root) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) }
        check(repository == null) {
            "$SETTING lies inside the git working tree $repository. Uploaded files must never be stored in a repository (NFR-17, NFR-4)."
        }
        return root
    }
}
