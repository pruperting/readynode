package uk.plumridge.readynode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets other apps (the package installer, OsmAnd, Kiwix…) open a download on Android 8 and 9, where downloads are
 * kept in the app's own Downloads folder. Newer Android versions use the shared Downloads collection instead.
 * Read-only, and only for files directly inside that folder.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "uk.plumridge.readynode.files";

    static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(f.getName()));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        File dir = getContext() == null ? null : getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        String name = uri.getLastPathSegment();
        if (dir == null || name == null || name.contains("/") || name.startsWith(".")) throw new FileNotFoundException();
        File f = new File(dir, name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return BrowseActivity.mimeFor(name == null ? "" : name, null);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }

    @Override
    public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
