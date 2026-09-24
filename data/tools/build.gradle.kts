// :data:tools — the portable toolsets and the bundle factory that stamps each tool with its owner.
// Device-bound toolsets (calendar, contacts, phone) are `:platform:android:tools` instead; what is here
// runs anywhere, which is exactly why `:platform:android:tools` can depend on this one small module rather
// than on all of `:data`.
plugins { id("aide.kmp.library") }
kotlin { sourceSets {
    commonMain.dependencies {
        api(project(":core:common"))
        api(project(":core:domain"))
        implementation(libs.okio)
    }
    // src/jvmShared: the direct java.io filesystem backend and the exp4j expression evaluator.
    androidMain.dependencies { implementation(libs.exp4j) }
    getByName("desktopMain").dependencies { implementation(libs.exp4j) }
} }
