package uk.plumridge.readynode;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** A folder picked with Android's storage picker (an SD card, a USB-C stick…), as a {@link Packs.Tree}. */
final class SafTree implements Packs.Tree {
    private final ContentResolver cr;
    private final Uri tree;
    private final String doc;

    private SafTree(ContentResolver cr, Uri tree, String doc) {
        this.cr = cr;
        this.tree = tree;
        this.doc = doc;
    }

    static SafTree of(Context c, Uri tree) {
        return new SafTree(c.getContentResolver(), tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /** {documentId, mime, size} of a child, or null. */
    private String[] find(String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        try (Cursor c = cr.query(kids, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null) while (c.moveToNext()) if (name.equals(c.getString(1))) return new String[]{c.getString(0), c.getString(2), String.valueOf(c.getLong(3))};
        }
        return null;
    }

    private Uri uri(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }

    @Override
    public Packs.Tree child(String name, boolean create) throws IOException {
        String[] f = find(name);
        if (f != null && Document.MIME_TYPE_DIR.equals(f[1])) return new SafTree(cr, tree, f[0]);
        if (!create) return null;
        Uri made = DocumentsContract.createDocument(cr, uri(doc), Document.MIME_TYPE_DIR, name);
        if (made == null) throw new IOException("couldn't make the folder " + name);
        return new SafTree(cr, tree, DocumentsContract.getDocumentId(made));
    }

    @Override
    public List<String[]> list() {
        List<String[]> out = new ArrayList<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        try (Cursor c = cr.query(kids, new String[]{Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null) while (c.moveToNext())
                out.add(new String[]{c.getString(0), Document.MIME_TYPE_DIR.equals(c.getString(1)) ? "d" : "f", String.valueOf(c.getLong(2))});
        }
        return out;
    }

    @Override
    public InputStream read(String name) throws IOException {
        String[] f = find(name);
        if (f == null) throw new FileNotFoundException(name);
        InputStream in = cr.openInputStream(uri(f[0]));
        if (in == null) throw new IOException("couldn't read " + name);
        return in;
    }

    @Override
    public OutputStream write(String name) throws IOException {
        delete(name);
        Uri made = DocumentsContract.createDocument(cr, uri(doc), "application/octet-stream", name);
        if (made == null) throw new IOException("couldn't make " + name);
        OutputStream out = cr.openOutputStream(made, "w");
        if (out == null) throw new IOException("couldn't write " + name);
        return out;
    }

    @Override
    public void delete(String name) throws IOException {
        String[] f = find(name);
        if (f != null) DocumentsContract.deleteDocument(cr, uri(f[0]));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        String[] f = find(from);
        if (f == null || DocumentsContract.renameDocument(cr, uri(f[0]), to) == null) throw new IOException("couldn't rename " + from);
    }
}
