"""JVM regression checks for all four clipboard Binder signatures (no phone required)."""
import pathlib
import subprocess
import sys
import tempfile

source = pathlib.Path(__file__).resolve().parent
java_home = pathlib.Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='clipboard-test-') as tmp:
    root = pathlib.Path(tmp)
    files = {
        'android/os/IInterface.java': 'package android.os; public interface IInterface {}',
        'android/content/IOnPrimaryClipChangedListener.java':
            'package android.content; public interface IOnPrimaryClipChangedListener extends android.os.IInterface {}',
        'android/util/Log.java': 'package android.util; public class Log { public static int e(String t,String m,Throwable e){return 0;} }',
        'android/content/ClipData.java': '''package android.content; public class ClipData {
            private CharSequence text;
            public static ClipData newPlainText(CharSequence label,CharSequence text) {
                ClipData c=new ClipData(); c.text=text; return c;
            }
            public int getItemCount(){return 1;}
            public Item getItemAt(int index){return new Item(text);}
            public static class Item { private CharSequence text; public Item(CharSequence t){text=t;}
                public CharSequence getText(){return text;}}
        }''',
    }
    managers = []
    cases = []
    for version, params, checks in [
        ('Legacy', 'String pkg', ''),
        ('User', 'String pkg,int user', 'check(user==0);'),
        ('Attribution', 'String pkg,String tag,int user', 'check(tag==null && user==0);'),
        ('Device', 'String pkg,String tag,int user,int device', 'check(tag==null && user==0 && device==0);'),
    ]:
        validation = 'check(pkg.equals("com.android.shell"));' + checks
        managers.append(f'''public static class {version} implements IInterface {{
            ClipData data; int added=0, removed=0;
            public void setPrimaryClip(ClipData c,{params}){{ {validation} data=c; }}
            public ClipData getPrimaryClip({params}){{ {validation} return data; }}
            public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener l,{params}){{ {validation} added++; }}
            public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener l,{params}){{ {validation} removed++; }}
        }}''')
        cases.append(f'''{{
            {version} manager=new {version}(); ClipboardManager bridge=new ClipboardManager(manager);
            check(bridge.getText()==null);
            check(bridge.setText("中文粘贴🙂")); check("中文粘贴🙂".contentEquals(bridge.getText()));
            check(bridge.addPrimaryClipChangedListener(null)); check(manager.added==1);
            check(bridge.removePrimaryClipChangedListener(null)); check(manager.removed==1);
        }}''')
    files['ClipboardTest.java'] = '''import android.content.*; import android.os.IInterface;
        import com.genymobile.scrcpy.wrappers.ClipboardManager;
        public class ClipboardTest {
        static void check(boolean b){if(!b)throw new AssertionError();}
    ''' + '\n'.join(managers) + '''
        public static class Missing implements IInterface {}
        public static class Denied extends Device {
            @Override public void setPrimaryClip(ClipData c,String pkg,String tag,int user,int device){throw new SecurityException();}
        }
        public static void main(String[] args){
    ''' + '\n'.join(cases) + '''
            ClipboardManager missing=new ClipboardManager(new Missing());
            check(!missing.setText("x")); check(missing.getText()==null);
            check(!missing.addPrimaryClipChangedListener(null)); check(!missing.removePrimaryClipChangedListener(null));
            check(!new ClipboardManager(new Denied()).setText("x"));
            System.out.println("PASS: legacy/user/attribution/device signatures; null/text/listeners; missing and denied interfaces");
        }}'''
    paths = []
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        paths.append(str(path))
    subprocess.run([str(java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', str(root),
                    *paths, str(source / 'ClipboardManager.java')], check=True)
    subprocess.run([str(java_home / 'bin/java'), '-cp', str(root), 'ClipboardTest'], check=True)
