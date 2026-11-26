import com.android.tools.smali.baksmali.Baksmali;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.DexFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;

/**
 * Disassemble a dex file into a smali directory, using the dexlib2/baksmali
 * classes that ship inside the apktool jar (their command line wrappers have
 * no main() there).
 *
 *   java -cp apktool.jar Disasm <in.dex> <out-dir>
 */
public class Disasm {

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        out.mkdirs();
        DexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(in)));
        BaksmaliOptions options = new BaksmaliOptions();
        options.apiLevel = 24;
        options.localsDirective = true;
        boolean ok = Baksmali.disassembleDexFile(dex, out, 4, options);
        System.out.println((ok ? "ok" : "failed") + " -> " + out.getAbsolutePath());
        if (!ok) {
            System.exit(1);
        }
    }
}
