"""Build a clipboard-only DEX patch using an existing JDK, Android SDK jar and Maven tools."""
import argparse
import pathlib
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--java-home', type=pathlib.Path, required=True)
parser.add_argument('--tools', type=pathlib.Path, required=True)
parser.add_argument('--android-jar', type=pathlib.Path, required=True)
parser.add_argument('--input', type=pathlib.Path, required=True, help='Original, unpatched WebSocket scrcpy server JAR')
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--old-version', required=True, help='Version string embedded in the input JAR')
parser.add_argument('--new-version', required=True, help='Version string to embed in the patched JAR')
args = parser.parse_args()
source = pathlib.Path(__file__).resolve().parent
java = str(args.java_home / 'bin' / 'java')
javac = str(args.java_home / 'bin' / 'javac')
import os

def run(*command):
    subprocess.run([str(value) for value in command], check=True)

with tempfile.TemporaryDirectory(prefix='scrcpy-clipboard-') as tmp:
    work = pathlib.Path(tmp)
    classes = work / 'classes'
    classes.mkdir()
    stub = work / 'IOnPrimaryClipChangedListener.java'
    stub.write_text('package android.content; public interface IOnPrimaryClipChangedListener extends android.os.IInterface {}')
    run(javac, '--release', '8', '-cp', args.android_jar, '-d', classes, stub, source / 'ClipboardManager.java')
    # Do not package the compile-only hidden API stub: Android supplies the real interface.
    patch_classes = sorted((classes / 'com/genymobile/scrcpy/wrappers').glob('ClipboardManager*.class'))
    if not patch_classes:
        raise RuntimeError('ClipboardManager classes were not compiled')
    run(java, '-cp', args.tools / 'r8.jar', 'com.android.tools.r8.D8', '--min-api', '21',
        '--lib', args.android_jar, '--classpath', classes, '--output', work, *patch_classes)
    libraries = os.pathsep.join(str(args.tools / name) for name in ('dexlib2.jar', 'guava.jar', 'jsr305.jar'))
    run(javac, '--release', '8', '-cp', libraries, '-d', classes, source / 'PatchDex.java')
    with zipfile.ZipFile(args.input) as archive:
        old_dex = archive.read('classes.dex')
        if args.new_version.encode() in old_dex:
            raise RuntimeError('Input is already patched; use the original server JAR')
        if args.old_version.encode() not in old_dex:
            raise RuntimeError('Input does not contain the expected server version')
        (work / 'original.dex').write_bytes(old_dex)
        run(java, '-cp', str(classes) + os.pathsep + libraries, 'PatchDex',
            work / 'original.dex', work / 'classes.dex', work / 'merged.dex', args.old_version, args.new_version)
        with zipfile.ZipFile(work / 'server.jar', 'w', zipfile.ZIP_DEFLATED) as output:
            for entry in archive.infolist():
                # JAR signatures would no longer be valid after replacing classes.dex.
                if entry.filename.upper().startswith('META-INF/') and entry.filename.upper().endswith(('.RSA', '.SF', '.DSA')):
                    continue
                output.writestr(entry, (work / 'merged.dex').read_bytes() if entry.filename == 'classes.dex'
                                else archive.read(entry.filename))
    with zipfile.ZipFile(work / 'server.jar') as result:
        assert result.testzip() is None
    args.output.write_bytes((work / 'server.jar').read_bytes())
    print('Created', args.output)
