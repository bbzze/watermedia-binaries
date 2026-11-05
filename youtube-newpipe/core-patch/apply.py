#!/usr/bin/env python3
"""Adds the NewPipe backend to a checkout of WaterMedia (run from the checkout's root folder)."""
import os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.getcwd()
NEWPIPE = os.environ.get('NEWPIPE_VERSION', 'v0.26.5')


def edit(path, old, new):
    full = os.path.join(ROOT, path)
    text = open(full, encoding='utf-8').read()
    if text.count(old) != 1:
        sys.exit('%s: expected exactly one match, found %d for:\n%s' % (path, text.count(old), old))
    open(full, 'w', encoding='utf-8').write(text.replace(old, new))
    print('patched', path)


# 1. the new class
dest = os.path.join(ROOT, 'src/main/java/org/watermedia/api/platform/web/NewPipeBackend.java')
shutil.copy(os.path.join(HERE, 'NewPipeBackend.java'), dest)
print('added', dest)

# 2. YtDlpPlatform: ask NewPipe when yt-dlp cannot run, keep the old method as ytDlpInfo
edit('src/main/java/org/watermedia/api/platform/web/YtDlpPlatform.java',
     '''    protected JsonObject info(final String url, final List<String> extraArgs, final int timeoutMs) throws IOException {
        final Path exe;''',
     '''    protected JsonObject info(final String url, final List<String> extraArgs, final int timeoutMs) throws IOException {
        // YOUTUBE AND SOUNDCLOUD: NO PROGRAM CAN BE STARTED ON iOS, SO THEY FALL BACK TO NewPipeExtractor, WHICH RUNS IN THE JVM
        if (NewPipeBackend.supports(url) && !NewPipeBackend.ytDlpOnly()) {
            if (NewPipeBackend.useFirst()) return NewPipeBackend.info(url, extraArgs, timeoutMs);
            try {
                return this.ytDlpInfo(url, extraArgs, timeoutMs);
            } catch (final IOException e) {
                if (!NewPipeBackend.isLaunchFailure(e)) throw e;
                NewPipeBackend.ytDlpUnavailable(e);
                return NewPipeBackend.info(url, extraArgs, timeoutMs);
            }
        }
        return this.ytDlpInfo(url, extraArgs, timeoutMs);
    }

    private JsonObject ytDlpInfo(final String url, final List<String> extraArgs, final int timeoutMs) throws IOException {
        final Path exe;''')

# 3. build.gradle: bundle the extractor (and what it needs) into the mod jar, like javacpp
# the extractor drags in annotation-only libraries (jsr305 and friends). The game already loads those as modules, and two
# modules exporting the same package (javax.annotation) stops Minecraft before it starts, so they are NOT bundled
edit('build.gradle',
     '''    include "org.bytedeco:javacpp:$javacppVersion"
''',
     '''    include "org.bytedeco:javacpp:$javacppVersion"
    include("com.github.TeamNewPipe:NewPipeExtractor:%s") {
        exclude group: 'com.google.code.findbugs', module: 'jsr305'
        exclude group: 'com.github.spotbugs', module: 'spotbugs-annotations'
        exclude group: 'org.jetbrains', module: 'annotations'
    }
''' % NEWPIPE)

# safety net: whatever way they get in, these packages never end up inside the WaterMedia jar
edit('build.gradle',
     "    exclude 'META-INF/versions/', 'META-INF/maven/**/*.xml', 'org/watermedia/binaries/**'",
     "    exclude 'META-INF/versions/', 'META-INF/maven/**/*.xml', 'org/watermedia/binaries/**'\n"
     "    exclude 'javax/annotation/**', 'edu/umd/cs/findbugs/annotations/**', 'org/jetbrains/annotations/**', 'org/intellij/lang/annotations/**', 'module-info.class'")
