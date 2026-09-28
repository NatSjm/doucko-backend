package cz.doucko.api

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.api`: interfaces and models generated from the contract
 * (`target/generated-sources/openapi`, NFR-12). OPEN, so every feature module can implement
 * the generated `*Api` interfaces and use `cz.doucko.api.model` types.
 */
@PackageInfo
@ApplicationModule(displayName = "API contract (generated)", type = ApplicationModule.Type.OPEN)
class ApiModule
