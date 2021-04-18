package appeng.client.gui.style;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.base.Preconditions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraftforge.resource.IResourceType;
import net.minecraftforge.resource.ISelectiveResourceReloadListener;

import appeng.core.AppEng;

/**
 * Manages AE2 GUI styles found in resource packs.
 */
public final class StyleManager {

    private static final Map<String, ScreenStyle> styleCache = new HashMap<>();
    public static final String PROP_INCLUDES = "includes";

    private static IResourceManager resourceManager;

    private static String getBasePath(String path) {
        int lastSep = path.lastIndexOf('/');
        if (lastSep == -1) {
            return "";
        } else {
            return path.substring(0, lastSep + 1);
        }
    }

    public static ScreenStyle loadStyleDoc(String path) throws IOException {
        ScreenStyle style = loadStyleDocInternal(path);
        // We only require the final style-document to be fully valid,
        // includes are allowed to be partially valid.
        style.validate();
        return style;
    }

    private static JsonObject loadMergedJsonTree(String path, Set<String> loadedFiles, Set<String> resourcePacks)
            throws IOException {
        Preconditions.checkArgument(path.startsWith("/"), "Path needs to start with slash");

        // The resource manager doesn't like relative paths like that, so we resolve them here
        if (path.contains("..")) {
            path = URI.create(path).normalize().toString();
        }

        if (!loadedFiles.add(path)) {
            throw new IllegalStateException("Recursive style includes: " + loadedFiles);
        }

        if (resourceManager == null) {
            throw new IllegalStateException("ResourceManager was not set. Was initialize called?");
        }

        String basePath = getBasePath(path);

        JsonObject document;
        try (IResource resource = resourceManager.getResource(AppEng.makeId(path.substring(1)))) {
            resourcePacks.add(resource.getPackName());
            document = ScreenStyle.GSON.fromJson(new InputStreamReader(resource.getInputStream()), JsonObject.class);
        }

        // Resolve the includes present in the document
        if (document.has(PROP_INCLUDES)) {
            String[] includes = ScreenStyle.GSON.fromJson(document.get(PROP_INCLUDES), String[].class);

            List<JsonObject> layers = new ArrayList<>();
            for (String include : includes) {
                layers.add(loadMergedJsonTree(basePath + include, loadedFiles, resourcePacks));
            }
            layers.add(document);
            document = combineLayers(layers);
        }

        return document;

    }

    // Builds a new JSON document from layered documents
    private static JsonObject combineLayers(List<JsonObject> layers) {
        JsonObject result = new JsonObject();

        // Start by copying over all properties layer-by-layer while overwriting properties set by
        // previous layers.
        for (JsonObject layer : layers) {
            for (Map.Entry<String, JsonElement> entry : layer.entrySet()) {
                result.add(entry.getKey(), entry.getValue());
            }
        }

        // Merge the following keys by merging their properties
        mergeObjectKeys("slots", layers, result);
        mergeObjectKeys("text", layers, result);
        mergeObjectKeys("palette", layers, result);
        mergeObjectKeys("images", layers, result);
        mergeObjectKeys("terminalStyle", layers, result);

        return result;
    }

    /**
     * Merges a single object property across multiple layers by merging the object keys. Higher layers win when there
     * is a conflict.
     */
    private static void mergeObjectKeys(String propertyName, List<JsonObject> layers, JsonObject target)
            throws JsonParseException {
        JsonObject mergedObject = null;
        for (JsonObject layer : layers) {
            JsonElement layerEl = layer.get(propertyName);
            if (layerEl != null) {
                if (!layerEl.isJsonObject()) {
                    throw new JsonParseException("Expected " + propertyName + " to be an object, but was: " + layerEl);
                }
                JsonObject layerObj = layerEl.getAsJsonObject();

                if (mergedObject == null) {
                    mergedObject = new JsonObject();
                }
                for (Map.Entry<String, JsonElement> entry : layerObj.entrySet()) {
                    mergedObject.add(entry.getKey(), entry.getValue());
                }
            }
        }

        if (mergedObject != null) {
            target.add(propertyName, mergedObject);
        }
    }

    private static ScreenStyle loadStyleDocInternal(String path) {

        ScreenStyle style = styleCache.get(path);
        if (style != null) {
            return style;
        }

        Set<String> resourcePacks = new HashSet<>();
        try {
            JsonObject document = loadMergedJsonTree(path, new HashSet<>(), resourcePacks);

            style = ScreenStyle.GSON.fromJson(document, ScreenStyle.class);

            style.validate();
        } catch (Exception e) {
            throw new JsonParseException("Failed to load style from " + path + " (packs: " + resourcePacks + ")", e);
        }

        styleCache.put(path, style);
        return style;
    }

    public static void initialize(IResourceManager resourceManager) {
        if (resourceManager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) resourceManager).addReloadListener(new ReloadListener());
        }
        setResourceManager(resourceManager);
    }

    private static void setResourceManager(IResourceManager resourceManager) {
        StyleManager.resourceManager = resourceManager;
        StyleManager.styleCache.clear();
    }

    private static class ReloadListener implements ISelectiveResourceReloadListener {
        @Override
        public void onResourceManagerReload(IResourceManager resourceManager,
                Predicate<IResourceType> resourcePredicate) {
            setResourceManager(resourceManager);
        }
    }

}
