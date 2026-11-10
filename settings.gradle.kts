rootProject.name = "Preflight"

include("preflight-api", "preflight-plugin", "preflight-example", "preflight-upgrade")

// Test-only plugins for the live scenarios in test/live. Never released.
include("livetest-fixture", "livetest-dependent")
project(":livetest-fixture").projectDir = file("test/live/fixture")
project(":livetest-dependent").projectDir = file("test/live/dependent")
