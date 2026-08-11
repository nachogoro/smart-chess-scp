dependencies {
    implementation(project(":scp-core"))
    implementation("io.github.nachogoro:simplechess:2.0.0")

    // Tests only. The orchestrator does not depend on any particular game controller - it is
    // written against the interface - but testing it exclusively against a stand-in leaves the
    // two halves of the system only ever measured against each other's assumptions, and the
    // places they disagreed were where the bugs were. See IntegrationTest.
    testImplementation(project(":game-controller"))
}
