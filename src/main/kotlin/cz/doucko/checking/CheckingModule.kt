package cz.doucko.checking

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.checking` (store spec answer-checking): the pure, deterministic answer
 * checker (FR-14 to FR-21, FR-23). This marker is the only class in the module that names Spring; every
 * other class depends on `java` and `kotlin` only (CheckingPurityTest).
 */
@PackageInfo
@ApplicationModule(displayName = "Answer checking")
class CheckingModule
