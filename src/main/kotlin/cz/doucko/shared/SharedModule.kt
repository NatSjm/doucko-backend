package cz.doucko.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.shared`: cross-cutting web plumbing every feature module relies on
 * (security baseline, problem+json, message catalog). OPEN, like `cz.doucko.api`, because it holds
 * infrastructure rather than a domain with its own public API.
 */
@PackageInfo
@ApplicationModule(displayName = "Shared web infrastructure", type = ApplicationModule.Type.OPEN)
class SharedModule
