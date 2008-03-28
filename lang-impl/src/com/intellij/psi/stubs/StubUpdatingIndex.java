/*
 * @author max
 */
package com.intellij.psi.stubs;

import com.intellij.lang.Language;
import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.IStubFileElementType;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.PersistentEnumerator;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

public class StubUpdatingIndex implements CustomImplementationFileBasedIndexExtension<Integer, SerializedStubTree, FileContent> {
  public static final ID<Integer, SerializedStubTree> INDEX_ID = ID.create("StubUpdatingIndex");
  private static final DataExternalizer<SerializedStubTree> KEY_EXTERNALIZER = new DataExternalizer<SerializedStubTree>() {
    public void save(final DataOutput out, final SerializedStubTree v) throws IOException {
      byte[] value = v.getBytes();
      out.writeInt(value.length);
      out.write(value);
    }

    public SerializedStubTree read(final DataInput in) throws IOException {
      int len = in.readInt();
      byte[] result = new byte[len];
      in.readFully(result);
      return new SerializedStubTree(result);
    }
  };

  private static final FileBasedIndex.InputFilter INPUT_FILTER = new FileBasedIndex.InputFilter() {
    public boolean acceptInput(final VirtualFile file) {
      final FileType fileType = file.getFileType();
      if (fileType instanceof LanguageFileType) {
        Language l = ((LanguageFileType)fileType).getLanguage();
        return LanguageParserDefinitions.INSTANCE.forLanguage(l).getFileNodeType() instanceof IStubFileElementType;
      }

      return false;
    }
  };
  private static final PersistentEnumerator.DataDescriptor<Integer> DATA_DESCRIPTOR = new PersistentEnumerator.DataDescriptor<Integer>() {
    public int getHashCode(final Integer value) {
      return value.hashCode();
    }

    public boolean isEqual(final Integer val1, final Integer val2) {
      return val1.equals(val2);
    }

    public void save(final DataOutput out, final Integer value) throws IOException {
      out.writeInt(value.intValue());
    }

    public Integer read(final DataInput in) throws IOException {
      return in.readInt();
    }
  };

  public ID<Integer, SerializedStubTree> getName() {
    return INDEX_ID;
  }

  public DataIndexer<Integer, SerializedStubTree, FileContent> getIndexer() {
    return new DataIndexer<Integer, SerializedStubTree, FileContent>() {
      public Map<Integer, SerializedStubTree> map(final FileContent inputData) {
        final Map<Integer, SerializedStubTree> result = new HashMap<Integer, SerializedStubTree>();
        if (!(inputData.getFile() instanceof NewVirtualFile)) return result;

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            final int key = Math.abs(FileBasedIndex.getFileId(inputData.getFile()));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(bytes);
            final LanguageFileType filetype = (LanguageFileType)inputData.getFile().getFileType();
            Language l = filetype.getLanguage();
            final IFileElementType type = LanguageParserDefinitions.INSTANCE.forLanguage(l).getFileNodeType();

            Project project = ProjectManager.getInstance().getDefaultProject(); // TODO

            PsiFile copy =
              PsiFileFactory.getInstance(project).createFileFromText(inputData.getFileName(), filetype, inputData.getContentAsText(), 1, false, false);

            final StubElement stub = ((IStubFileElementType)type).getBuilder().buildStubTree(copy);

            SerializationManager.getInstance().serialize(stub, stream);
            result.put(key, new SerializedStubTree(bytes.toByteArray()));
          }
        });

        return result;
      }
    };
  }

  public PersistentEnumerator.DataDescriptor<Integer> getKeyDescriptor() {
    return DATA_DESCRIPTOR;
  }

  public DataExternalizer<SerializedStubTree> getValueExternalizer() {
    return KEY_EXTERNALIZER;
  }

  public FileBasedIndex.InputFilter getInputFilter() {
    return INPUT_FILTER;
  }

  public boolean dependsOnFileContent() {
    return true;
  }

  public int getVersion() {
    return 2;
  }

  public UpdatableIndex<Integer, SerializedStubTree, FileContent> createIndexImplementation(final IndexStorage<Integer, SerializedStubTree> storage) {
    return new MyIndex(storage, getIndexer());
  }

  private static class MyIndex extends MapReduceIndex<Integer, SerializedStubTree, FileContent> {
    private Map<StubIndexKey, Map<Object, TIntArrayList>> myOld;
    private Map<StubIndexKey, Map<Object, TIntArrayList>> myNew;

    public MyIndex(final IndexStorage<Integer, SerializedStubTree> storage,
                   final DataIndexer<Integer, SerializedStubTree, FileContent> indexer) {
      super(indexer, storage);
    }

    public void update(final int inputId,
                       @Nullable final FileContent content,
                       @Nullable final FileContent oldContent) throws StorageException {
      super.update(inputId, content, oldContent);
      Set<StubIndexKey> allIndices = new HashSet<StubIndexKey>();
      if (myOld == null) myOld = Collections.emptyMap();
      if (myNew == null) myNew = Collections.emptyMap();
      allIndices.addAll(myOld.keySet());
      allIndices.addAll(myNew.keySet());
      for (StubIndexKey key : allIndices) {
        final Map oldMap = myOld.get(key);
        final Map newMap = myNew.get(key);

        ((StubIndexImpl)StubIndex.getInstance()).updateIndex(key, inputId, oldMap != null ? oldMap : Collections.emptyMap(),
                                                             newMap != null ? newMap : Collections.emptyMap());
      }
    }

    protected Map<Integer, SerializedStubTree> mapOld(final FileContent oldContent) {
      myOld = null;
      final Map<Integer, SerializedStubTree> result = super.mapOld(oldContent);
      if (!result.isEmpty()) {
        final SerializedStubTree stub = result.values().iterator().next();
        myOld = new StubTree((PsiFileStub)stub.getStub()).indexStubTree();
      }

      return result;
    }

    protected Map<Integer, SerializedStubTree> mapNew(final FileContent content) {
      myNew = null;
      final Map<Integer, SerializedStubTree> result = super.mapNew(content);
      if (!result.isEmpty()) {
        final SerializedStubTree stub = result.values().iterator().next();
        myNew = new StubTree((PsiFileStub)stub.getStub()).indexStubTree();
      }
      return super.mapNew(content);
    }
  }
}