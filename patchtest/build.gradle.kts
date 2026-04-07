plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation("com.android.tools.smali:smali-dexlib2:3.0.7")
    implementation("com.android.tools.smali:smali:3.0.7")
    implementation("com.android.tools.smali:smali-baksmali:3.0.7")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}
