/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gapid.views;

import static com.google.gapid.proto.service.gfxapi.GfxAPI.ResourceType.TextureResource;
import static com.google.gapid.util.GeoUtils.bottomLeft;
import static com.google.gapid.util.Loadable.MessageType.Error;
import static com.google.gapid.util.Loadable.MessageType.Info;
import static com.google.gapid.util.Paths.resourceAfter;
import static com.google.gapid.util.Paths.thumbnail;
import static com.google.gapid.widgets.Widgets.createComposite;
import static com.google.gapid.widgets.Widgets.createTableColumn;
import static com.google.gapid.widgets.Widgets.createTableViewer;
import static com.google.gapid.widgets.Widgets.ifNotDisposed;
import static com.google.gapid.widgets.Widgets.packColumns;
import static com.google.gapid.widgets.Widgets.sorting;
import static com.google.gapid.widgets.Widgets.withAsyncRefresh;
import static java.util.logging.Level.FINE;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.UnsignedLongs;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gapid.image.FetchedImage;
import com.google.gapid.models.AtomStream;
import com.google.gapid.models.AtomStream.AtomIndex;
import com.google.gapid.models.Capture;
import com.google.gapid.models.Models;
import com.google.gapid.models.Resources;
import com.google.gapid.proto.image.Image;
import com.google.gapid.proto.service.Service;
import com.google.gapid.proto.service.gfxapi.GfxAPI;
import com.google.gapid.proto.service.path.Path;
import com.google.gapid.rpc.Rpc;
import com.google.gapid.rpc.RpcException;
import com.google.gapid.rpc.SingleInFlight;
import com.google.gapid.rpc.UiCallback;
import com.google.gapid.rpc.UiErrorCallback;
import com.google.gapid.rpc.Rpc.Result;
import com.google.gapid.server.Client;
import com.google.gapid.server.Client.DataUnavailableException;
import com.google.gapid.util.Loadable;
import com.google.gapid.util.Messages;
import com.google.gapid.util.Paths;
import com.google.gapid.widgets.ImagePanel;
import com.google.gapid.widgets.LoadableImage;
import com.google.gapid.widgets.LoadingIndicator;
import com.google.gapid.widgets.Theme;
import com.google.gapid.widgets.VisibilityTrackingTableViewer;
import com.google.gapid.widgets.Widgets;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * View that displays the texture resources of the current capture.
 */
public class TextureView extends Composite
    implements Tab, Capture.Listener, Resources.Listener, AtomStream.Listener {
  protected static final Logger LOG = Logger.getLogger(TextureView.class.getName());

  private final Client client;
  private final Models models;
  private final SingleInFlight rpcController = new SingleInFlight();
  private final GotoAction gotoAction;
  private final TableViewer textureTable;
  private final ImageProvider imageProvider;
  protected final Loadable loading;
  private final ImagePanel imagePanel;

  public TextureView(Composite parent, Client client, Models models, Widgets widgets) {
    super(parent, SWT.NONE);
    this.client = client;
    this.models = models;
    this.gotoAction = new GotoAction(this, widgets.theme,
        a -> models.atoms.selectAtoms(AtomIndex.forCommand(a), true));

    setLayout(new FillLayout(SWT.VERTICAL));
    SashForm splitter = new SashForm(this, SWT.VERTICAL);
    textureTable = createTableViewer(splitter, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
    imageProvider = new ImageProvider(client, textureTable, widgets.loading);
    initTextureSelector(textureTable, imageProvider);

    Composite imageAndToolbar = createComposite(splitter, new GridLayout(2, false));
    ToolBar toolBar = new ToolBar(imageAndToolbar, SWT.VERTICAL | SWT.FLAT);
    imagePanel = createImagePanel(imageAndToolbar, widgets);
    loading = imagePanel.getLoading();

    splitter.setWeights(models.settings.texturesSplitterWeights);
    addListener(SWT.Dispose, e -> models.settings.texturesSplitterWeights = splitter.getWeights());

    toolBar.setLayoutData(new GridData(SWT.LEFT, SWT.FILL, false, true));
    imagePanel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    imagePanel.createToolbar(toolBar, widgets.theme, true);
    gotoAction.createToolItem(toolBar);

    models.capture.addListener(this);
    models.atoms.addListener(this);
    models.resources.addListener(this);
    addListener(SWT.Dispose, e -> {
      models.capture.removeListener(this);
      models.atoms.removeListener(this);
      models.resources.removeListener(this);
      gotoAction.dispose();
      imageProvider.reset();
    });

    textureTable.getTable().addListener(SWT.Selection, e -> updateSelection());
  }

  protected void setImage(FetchedImage result) {
    imagePanel.setImage(result);
  }

  private static void initTextureSelector(TableViewer viewer, ImageProvider imageProvider) {
    viewer.setContentProvider(ArrayContentProvider.getInstance());
    viewer.setLabelProvider(new ViewLabelProvider(imageProvider));

    sorting(viewer,
        createTableColumn(viewer, "Type", Data::getType,
            (d1, d2) -> d1.getType().compareTo(d2.getType())),
        createTableColumn(viewer, "ID", Data::getId, d -> imageProvider.getImage(d),
            (d1, d2) -> UnsignedLongs.compare(d1.getSortId(), d2.getSortId())),
        createTableColumn(viewer, "Name", Data::getLabel,
            (d1, d2) -> d1.getLabel().compareTo(d2.getLabel())),
        createTableColumn(viewer, "Width", Data::getWidth,
            (d1, d2) -> Integer.compare(d1.getSortWidth(), d2.getSortWidth())),
        createTableColumn(viewer, "Height", Data::getHeight,
            (d1, d2) -> Integer.compare(d1.getSortHeight(), d2.getSortHeight())),
        createTableColumn(viewer, "Levels", Data::getLevels,
            (d1, d2) -> Integer.compare(d1.getSortLevels(), d2.getSortLevels())),
        createTableColumn(viewer, "Format", Data::getFormat,
            (d1, d2) -> d1.getFormat().compareTo(d2.getFormat())));
  }

  private static ImagePanel createImagePanel(Composite parent, Widgets widgets) {
    ImagePanel panel = new ImagePanel(parent, widgets);
    return panel;
  }

  @Override
  public Control getControl() {
    return this;
  }

  @Override
  public void reinitialize() {
    onCaptureLoadingStart(false);
    updateTextures(true);
  }

  @Override
  public void onCaptureLoadingStart(boolean maintainState) {
    loading.showMessage(Info, Messages.LOADING_CAPTURE);
    clear();
  }

  @Override
  public void onCaptureLoaded(Loadable.Message error) {
    if (error != null) {
      loading.showMessage(Error, Messages.CAPTURE_LOAD_FAILURE);
      clear();
    }
  }

  @Override
  public void onResourcesLoaded() {
    updateTextures(true);
  }

  @Override
  public void onAtomsSelected(AtomIndex path) {
    updateTextures(false);
  }

  private void updateTextures(boolean resourcesChanged) {
    if (models.resources.isLoaded() && models.atoms.getSelectedAtoms() != null) {
      imageProvider.reset();
      List<Data> textures = Lists.newArrayList();
      for (Service.ResourcesByType resources : models.resources.getResources()) {
        addTextures(textures, resources);
      }

      ViewerComparator comparator = textureTable.getComparator();
      textureTable.setComparator(null);
      int selection = textureTable.getTable().getSelectionIndex();
      textureTable.setInput(textures);
      packColumns(textureTable.getTable());

      if (!resourcesChanged && selection >= 0 && selection < textures.size()) {
        textureTable.getTable().select(selection);
      }
      textureTable.setComparator(comparator);

      if (textures.isEmpty()) {
        loading.showMessage(Info, Messages.NO_TEXTURES);
      } else if (textureTable.getTable().getSelectionIndex() < 0) {
        loading.showMessage(Info, Messages.SELECT_TEXTURE);
      }
      updateSelection();
    } else {
      loading.showMessage(Info, Messages.SELECT_ATOM);
      clear();
    }
  }

  private void clear() {
    gotoAction.clear();
    textureTable.setInput(Collections.emptyList());
    textureTable.getTable().requestLayout();
    imageProvider.reset();
  }

  private void updateSelection() {
    int selection = textureTable.getTable().getSelectionIndex();
    if (selection < 0) {
      setImage(null);
      gotoAction.clear();
    } else {
      loading.startLoading();
      Data data = (Data)textureTable.getElementAt(selection);
      rpcController.start().listen(FetchedImage.load(client, data.path.getResourceData()),
          new UiErrorCallback<FetchedImage, FetchedImage, String>(this, LOG) {
        @Override
        protected ResultOrError<FetchedImage, String> onRpcThread(Result<FetchedImage> result)
            throws RpcException, ExecutionException {
          try {
            return success(result.get());
          } catch (DataUnavailableException e) {
            return error(e.getMessage());
          }
        }

        @Override
        protected void onUiThreadSuccess(FetchedImage result) {
          setImage(result);
        }

        @Override
        protected void onUiThreadError(String error) {
          setImage(null);
          loading.showMessage(Info, error);
        }
      });
      gotoAction.setAtomIds(data.info.getAccessesList(), data.path.getResourceData().getAfter());
    }
  }

  private void addTextures(List<Data> textures, Service.ResourcesByType resources) {
    if (resources == null || resources.getResourcesList().size() == 0) {
      return;
    }

    if (resources.getType() != TextureResource) {
      // Ignore non-texture resources (and unknown texture types).
      return;
    }

    AtomIndex range = models.atoms.getSelectedAtoms();
    Widgets.Refresher refresher = withAsyncRefresh(textureTable);
    for (Service.Resource info : resources.getResourcesList()) {
      if (Paths.compare(firstAccess(info), range.getCommand()) <= 0) {
        Data data = new Data(resourceAfter(range, info.getId()), info);
        textures.add(data);
        data.load(client, textureTable.getTable(), refresher);
      }
    }
  }

  private static Path.Command firstAccess(Service.Resource info) {
    return (info.getAccessesCount() == 0) ? null : info.getAccesses(0);
  }

  /**
   * Texture metadata.
   */
  private static class Data {
    public final Path.Any path;
    public final Service.Resource info;
    protected AdditionalInfo imageInfo;

    public Data(Path.Any path, Service.Resource info) {
      this.path = path;
      this.info = info;
      this.imageInfo = AdditionalInfo.NULL;
    }

    public String getType() {
      return imageInfo.getType();
    }

    public String getId() {
      return info.getHandle();
    }

    public long getSortId() {
      return info.getOrder();
    }

    public String getLabel() {
      return info.getLabel();
    }

    public String getWidth() {
      return imageInfo.getWidth();
    }

    public int getSortWidth() {
      return imageInfo.level0.getWidth();
    }

    public String getHeight() {
      return imageInfo.getHeight();
    }

    public int getSortHeight() {
      return imageInfo.level0.getHeight();
    }

    public String getLevels() {
      return imageInfo.getLevels();
    }

    public int getSortLevels() {
      return imageInfo.levelCount;
    }

    public String getFormat() {
      return imageInfo.getFormat();
    }

    public void load(Client client, Widget widget, Widgets.Refresher refresher) {
      Rpc.listen(
          client.get(path), new UiCallback<Service.Value, AdditionalInfo>(widget, LOG) {
        @Override
        protected AdditionalInfo onRpcThread(Result<Service.Value> result)
            throws RpcException, ExecutionException {
          try {
            return AdditionalInfo.from(result.get());
          } catch (DataUnavailableException e) {
            LOG.log(FINE, "Texture info unavailable: {0}", e.getMessage());
            return AdditionalInfo.NULL;
          }
        }

        @Override
        protected void onUiThread(AdditionalInfo result) {
          imageInfo = result;
          refresher.refresh();
        }
      });
    }

    private static class AdditionalInfo {
      public static final AdditionalInfo NULL =
          new AdditionalInfo("<unknown>", Image.Info.getDefaultInstance(), 0);
      public static final AdditionalInfo NULL_2D =
          new AdditionalInfo("2D", Image.Info.getDefaultInstance(), 0);
      public static final AdditionalInfo NULL_CUBEMAP =
          new AdditionalInfo("Cubemap", Image.Info.getDefaultInstance(), 0);

      public final Image.Info level0;
      public final int levelCount;
      public final String typeLabel;

      public AdditionalInfo(String typeLabel, Image.Info level0, int levelCount) {
        this.level0 = level0;
        this.levelCount = levelCount;
        this.typeLabel = typeLabel;
      }

      public static AdditionalInfo from(Service.Value value) {
        GfxAPI.ResourceData data = value.getResourceData();
        GfxAPI.Texture texture = data.getTexture();
        switch (texture.getTypeCase()) {
          case TEXTURE_2D:
            GfxAPI.Texture2D t = texture.getTexture2D();
            return (t.getLevelsCount() == 0) ? NULL_2D :
                new AdditionalInfo("2D", t.getLevels(0), t.getLevelsCount());
          case CUBEMAP:
            GfxAPI.Cubemap c = texture.getCubemap();
            return (c.getLevelsCount() == 0) ? NULL_CUBEMAP :
                new AdditionalInfo("Cubemap", c.getLevels(0).getNegativeX(), c.getLevelsCount());
          default:
            return NULL;
        }
      }

      public String getType() {
        return typeLabel;
      }

      public String getWidth() {
        return (levelCount == 0) ? "" : String.valueOf(level0.getWidth());
      }

      public String getHeight() {
        return (levelCount == 0) ? "" : String.valueOf(level0.getHeight());
      }

      public String getFormat() {
        return (levelCount == 0) ? "" : level0.getFormat().getName();
      }

      public String getLevels() {
        return (levelCount == 0) ? "" : String.valueOf(levelCount);
      }
    }
  }

  /**
   * Action for the {@link ToolItem} that allows the user to jump to references of the currently
   * displayed texture.
   */
  private static class GotoAction {
    private final Theme theme;
    private final Consumer<Path.Command> listener;
    private final Menu popupMenu;
    private ToolItem item;
    private List<Path.Command> atomIds = Collections.emptyList();

    public GotoAction(Composite parent, Theme theme, Consumer<Path.Command> listener) {
      this.theme = theme;
      this.listener = listener;
      this.popupMenu = new Menu(parent);
    }

    public ToolItem createToolItem(ToolBar bar) {
      item = Widgets.createToolItem(bar, theme.jump(), e -> {
        popupMenu.setLocation(bar.toDisplay(bottomLeft(((ToolItem)e.widget).getBounds())));
        popupMenu.setVisible(true);
      }, "Jump to texture reference");
      item.setEnabled(!atomIds.isEmpty());
      return item;
    }

    public void dispose() {
      popupMenu.dispose();
    }

    public void clear() {
      atomIds = Collections.emptyList();
      update(null);
    }

    public void setAtomIds(List<Path.Command> ids, Path.Command selection) {
      atomIds = ids;
      update(selection);
    }

    private void update(Path.Command selection) {
      for (MenuItem child : popupMenu.getItems()) {
        child.dispose();
      }

      for (int i = 0; i < atomIds.size(); i++) {
        Path.Command id = atomIds.get(i);
        MenuItem child = Widgets.createMenuItem(popupMenu, Formatter.atomIndex(id)/* + ": " + atoms.get(id).getName()*/, 0,
            e -> listener.accept(id));
        /*
        if (id <= selection && (i == atomIds.size() - 1 || atomIds.get(i + 1) > selection)) {
          child.setImage(theme.arrow());
        }
        */
      }
      item.setEnabled(!atomIds.isEmpty());
    }
  }

  /**
   * Image provider for the texture selector.
   */
  private static class ImageProvider implements LoadingIndicator.Repaintable {
    private static final int SIZE = DPIUtil.autoScaleUp(18);

    private final Client client;
    private final TableViewer viewer;
    private final LoadingIndicator loading;
    private final Map<Data, LoadableImage> images = Maps.newIdentityHashMap();

    public ImageProvider(Client client, TableViewer viewer, LoadingIndicator loading) {
      this.client = client;
      this.viewer = viewer;
      this.loading = loading;
    }

    public void load(Data data) {
      LoadableImage image = getLoadableImage(data);
      if (image != null) {
        image.load();
      }
    }

    public void unload(Data data) {
      LoadableImage image = images.get(data);
      if (image != null) {
        image.unload();
      }
    }

    public org.eclipse.swt.graphics.Image getImage(Data data) {
      return getLoadableImage(data).getImage();
    }

    @Override
    public void repaint() {
      ifNotDisposed(viewer.getControl(), () -> viewer.refresh());
    }

    private LoadableImage getLoadableImage(Data data) {
      LoadableImage image = images.get(data);
      if (image == null) {
        image = LoadableImage.newBuilder(loading)
            .small()
            .forImageData(() -> loadImage(data))
            .onErrorReturnNull()
            .build(viewer.getTable(), this);
        images.put(data, image);
      }
      return image;
    }

    private ListenableFuture<ImageData> loadImage(Data data) {
      return FetchedImage.loadThumbnail(client, thumbnail(data.path.getResourceData(), SIZE));
    }

    public void reset() {
      for (LoadableImage image : images.values()) {
        image.dispose();
      }
      images.clear();
    }
  }

  private static class ViewLabelProvider extends LabelProvider
      implements VisibilityTrackingTableViewer.Listener {
    private final ImageProvider imageProvider;

    public ViewLabelProvider(ImageProvider imageProvider) {
      this.imageProvider = imageProvider;
    }

    @Override
    public void onShow(TableItem item) {
      Object element = item.getData();
      if (element instanceof Data) {
        imageProvider.load((Data)element);
      }
    }

    @Override
    public void onHide(TableItem item) {
      Object element = item.getData();
      if (element instanceof Data) {
        imageProvider.unload((Data)element);
      }
    }
  }
}
