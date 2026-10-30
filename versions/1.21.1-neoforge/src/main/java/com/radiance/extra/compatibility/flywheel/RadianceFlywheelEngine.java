package com.radiance.extra.compatibility.flywheel;

import com.radiance.api.render.InstanceScene;
import com.radiance.extra.mixins.compatibility.flywheel.FlywheelLevelRendererTicksAccessor;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.UnitPlan;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class RadianceFlywheelEngine implements Engine {
    private static final boolean SOURCE_AUDIT = Boolean.getBoolean("radiance.extra.sourceAudit");
    private static final java.util.concurrent.atomic.LongAdder MODEL_CAPTURES =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MODEL_UPLOADS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder INSTANCE_WRITES =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder QUEUED_VISITS =
            new java.util.concurrent.atomic.LongAdder();

    static long[] workCounters() {
        return new long[] {
            MODEL_CAPTURES.sum(), MODEL_UPLOADS.sum(), INSTANCE_WRITES.sum(), QUEUED_VISITS.sum()
        };
    }

    private static final int MAX_ORIGIN_DISTANCE_SQUARED = 256 * 256;

    /** Default on; false keeps every model until the level unloads (A/B and fallback only). */
    private static final boolean RELEASE_UNUSED_MODELS =
            Boolean.parseBoolean(System.getProperty("radiance.flywheelModelRelease", "true"));

    private static final Object INSTANCE_TRANSFER_LOCK = new Object();
    private final ClientLevel level;
    private final InstanceScene nativeEngine;
    private final AtomicLong instanceIds = new AtomicLong(1);
    private final Map<Model, ModelState> models = new IdentityHashMap<>();
    private final Map<InstancerKey, RadianceInstancer<?>> instancers = new LinkedHashMap<>();
    private final Set<Handle<?>> pendingInstances =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<ModelState> pendingModels = new ArrayList<>();
    private final GlobalContext globalContext = new GlobalContext();
    private final List<Embedding> embeddings = new ArrayList<>();
    private Vec3i renderOrigin = Vec3i.ZERO;
    private boolean deleted;

    RadianceFlywheelEngine(LevelAccessor level) {
        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalArgumentException("Radiance Flywheel backend requires ClientLevel");
        }
        this.level = clientLevel;
        this.nativeEngine = new InstanceScene();
        FlywheelHandoffs.visualizable(
                dev.engine_room.flywheel.lib.visualization.VisualizationHelper::canVisualize);
    }

    @Override
    public VisualizationContext createVisualizationContext() {
        ensureLive();
        return globalContext;
    }

    @Override
    public Plan<RenderContext> createFramePlan() {
        return UnitPlan.of();
    }

    @Override
    public Vec3i renderOrigin() {
        return renderOrigin;
    }

    @Override
    public synchronized boolean updateRenderOrigin(Camera camera) {
        Vec3 cameraPos = camera.getPosition();
        double dx = renderOrigin.getX() - cameraPos.x;
        double dy = renderOrigin.getY() - cameraPos.y;
        double dz = renderOrigin.getZ() - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz <= MAX_ORIGIN_DISTANCE_SQUARED) return false;
        // Deleted visuals no longer receive source origin updates. Keep their basis alive until
        // the replacement publication retires them, rather than moving their retained geometry.
        for (var instancer : instancers.values())
            synchronized (instancer) {
                for (var handle : instancer.instances)
                    if (handle.sourceOwner != null && handle.sourceOwner.retain()) return false;
            }
        renderOrigin = BlockPos.containing(cameraPos);
        globalContext.space.pose(
                new org.joml.Matrix4d()
                        .translation(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ()),
                new Matrix3f());
        synchronized (this) {
            instancers.values().forEach(RadianceInstancer::markAllDirty);
        }
        return true;
    }

    @Override
    public synchronized void lightSections(LongSet sections) {
        ensureLive();
    }

    @Override
    public void onLightUpdate(SectionPos sectionPos, LightLayer layer) {
        ensureLive();
    }

    @Override
    public synchronized void render(RenderContext context) {
        ensureLive();
        nativeEngine.beginFrame(
                renderOrigin.getX(),
                renderOrigin.getY(),
                renderOrigin.getZ(),
                renderTicks(context));
        uploadPendingModels();
        for (var handle : pendingInstances.toArray(Handle<?>[]::new)) {
            if (SOURCE_AUDIT) QUEUED_VISITS.increment();
            pendingInstances.remove(handle);
            if (handle.engine != this) continue;
            synchronized (handle.owner) {
                handle.flush();
                if ((handle.deleted || handle.owner.context.deleted()) && !handle.nativeCreated) {
                    handle.sourceOwner(null);
                    handle.owner.instances.remove(handle);
                }
            }
        }
        if (com.radiance.api.render.RenderRoutes.active())
            try (var request =
                    com.radiance.api.audit.DrawingAudit.begin(
                            "PERSISTENT_INSTANCE_SCENE",
                            "radiance_extra:flywheel",
                            "DIRECT",
                            "WORLD")) {
                nativeEngine.render();
                com.radiance.api.audit.DrawingAudit.consumed("native.instance-scene");
            }
        else nativeEngine.render();
        FlywheelHandoffs.completed(level);
        if (removeEmptyInstancers()) releaseUnusedModels();
        for (int i = embeddings.size() - 1; i >= 0; i--) {
            var embedding = embeddings.get(i);
            if (embedding.deleted() && !uses(embedding)) {
                embedding.space.close();
                embeddings.remove(i);
            }
        }
        FlywheelHandoffs.prune();
    }

    private boolean uses(Context context) {
        for (var instancer : instancers.values())
            synchronized (instancer) {
                if (!instancer.instances.isEmpty() && instancer.context.dependsOn(context))
                    return true;
            }
        return false;
    }

    private InstancerProvider ownedProvider(InstancerProvider provider) {
        var owner = FlywheelHandoffs.current();
        if (owner == null) return provider;
        return new InstancerProvider() {
            @Override
            public <I extends Instance> Instancer<I> instancer(
                    InstanceType<I> type, Model model, int bias) {
                @SuppressWarnings("unchecked")
                var target = (RadianceInstancer<I>) provider.instancer(type, model, bias);
                return new Instancer<>() {
                    public I createInstance() {
                        return target.createOwned(owner);
                    }

                    public void stealInstance(I instance) {
                        target.stealInstance(instance);
                        if (instance.handle() instanceof RadianceFlywheelEngine.Handle<?> handle)
                            handle.sourceOwner(owner);
                    }
                };
            }
        };
    }

    @Override
    public synchronized void renderCrumbling(
            RenderContext context, List<CrumblingBlock> crumblingBlocks) {
        ensureLive();
        int count = crumblingBlocks.stream().mapToInt(block -> block.instances().size()).sum();
        if (count == 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer instances = stack.mallocLong(count);
            LongBuffer positions = stack.mallocLong(count);
            IntBuffer progress = stack.mallocInt(count);
            IntBuffer textures = stack.mallocInt(count);
            for (CrumblingBlock block : crumblingBlocks) {
                for (Instance instance : block.instances()) {
                    if (instance.handle() instanceof Handle<?> handle && !handle.deleted) {
                        instances.put(handle.id);
                        positions.put(block.pos().asLong());
                        progress.put(block.progress());
                        var texture =
                                net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                                        "textures/block/destroy_stage_"
                                                + block.progress()
                                                + ".png");
                        textures.put(
                                net.minecraft.client.Minecraft.getInstance()
                                        .getTextureManager()
                                        .getTexture(texture)
                                        .getId());
                    }
                }
            }
            int actual = instances.position();
            instances.flip();
            positions.flip();
            progress.flip();
            textures.flip();
            if (actual > 0) {
                nativeEngine.crumbling(instances, positions, progress, textures);
            }
        }
    }

    @Override
    public synchronized void delete() {
        if (deleted) return;
        deleted = true;
        for (RadianceInstancer<?> instancer : instancers.values()) instancer.deleteAll();
        instancers.clear();
        for (ModelState model : models.values()) model.close();
        models.clear();
        pendingModels.clear();
        pendingInstances.clear();
        nativeEngine.close();
        for (int i = embeddings.size() - 1; i >= 0; i--) embeddings.get(i).space.close();
        embeddings.clear();
        globalContext.space.close();
    }

    private synchronized <I extends Instance> Instancer<I> instancer(
            Context context, InstanceType<I> type, Model model, int bias) {
        ensureLive();
        FlywheelInstanceAdapter adapter = FlywheelInstanceAdapter.require(type);
        ModelState modelState = models.get(model);
        if (modelState == null) {
            modelState = ModelState.capture(model);
            models.put(model, modelState);
            pendingModels.add(modelState);
        }
        final ModelState capturedModel = modelState;
        InstancerKey key = new InstancerKey(context, type, model, bias);
        @SuppressWarnings("unchecked")
        RadianceInstancer<I> result =
                (RadianceInstancer<I>)
                        instancers.computeIfAbsent(
                                key,
                                ignored ->
                                        new RadianceInstancer<>(
                                                type,
                                                adapter,
                                                model,
                                                capturedModel,
                                                bias,
                                                context));
        return result;
    }

    /**
     * Same clock as Flywheel's frame uniforms (LevelRenderer ticks plus partial tick). The world
     * game time is unsuitable: it grows without bound and loses sub-tick precision in float.
     */
    static double renderTicks(RenderContext context) {
        return ((FlywheelLevelRendererTicksAccessor) context.renderer()).radiance$getTicks()
                + (double) context.partialTick();
    }

    private void uploadPendingModels() {
        for (ModelState model : pendingModels) if (!model.released) model.upload(nativeEngine);
        pendingModels.clear();
    }

    /**
     * Like Flywheel's own backends, an instancer is dropped once it has no instances. Keeping it
     * would pin its model until the level unloads, which for per-structure models (contraptions,
     * unloaded sub-levels) grows with every structure ever seen. A visual that still holds a
     * dropped instancer is forwarded to the live instancer for the same key.
     */
    private boolean removeEmptyInstancers() {
        if (!RELEASE_UNUSED_MODELS) {
            instancers
                    .values()
                    .removeIf(
                            instancer ->
                                    instancer.instances.isEmpty() && instancer.context.deleted());
            return false;
        }
        return instancers.values().removeIf(RadianceInstancer::detachIfEmpty);
    }

    /** Frees the native mesh, BLAS and buffers of every model no remaining instancer uses. */
    private void releaseUnusedModels() {
        Set<ModelState> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RadianceInstancer<?> instancer : instancers.values()) used.add(instancer.model);
        Iterator<ModelState> iterator = models.values().iterator();
        while (iterator.hasNext()) {
            ModelState model = iterator.next();
            if (used.contains(model)) continue;
            iterator.remove();
            model.release(nativeEngine);
        }
    }

    private void ensureLive() {
        if (deleted) throw new IllegalStateException("Flywheel engine is deleted");
    }

    private interface Context {
        InstancerProvider provider();

        Vec3i origin();

        com.radiance.api.render.RenderSpace space();

        boolean deleted();

        boolean dependsOn(Context ancestor);
    }

    private final class GlobalContext implements VisualizationContext, Context {
        private final com.radiance.api.render.RenderSpace space =
                new com.radiance.api.render.RenderSpace();
        private final InstancerProvider provider = this::instancer;

        private <I extends Instance> Instancer<I> instancer(
                InstanceType<I> type, Model model, int bias) {
            return RadianceFlywheelEngine.this.instancer(this, type, model, bias);
        }

        @Override
        public InstancerProvider instancerProvider() {
            return ownedProvider(provider);
        }

        @Override
        public InstancerProvider provider() {
            return provider;
        }

        @Override
        public Vec3i renderOrigin() {
            return RadianceFlywheelEngine.this.renderOrigin;
        }

        @Override
        public Vec3i origin() {
            return renderOrigin();
        }

        @Override
        public boolean deleted() {
            return false;
        }

        @Override
        public boolean dependsOn(Context ancestor) {
            return this == ancestor;
        }

        @Override
        public com.radiance.api.render.RenderSpace space() {
            return space;
        }

        @Override
        public VisualEmbedding createEmbedding(Vec3i origin) {
            return new Embedding(this, origin);
        }
    }

    private final class Embedding implements VisualEmbedding, Context {
        private final Context parent;
        private final Vec3i origin;
        private final InstancerProvider provider = this::instancer;
        private final com.radiance.api.render.RenderSpace space;
        private volatile boolean deleted;

        private Embedding(Context parent, Vec3i origin) {
            this.parent = parent;
            this.origin = origin;
            this.space = new com.radiance.api.render.RenderSpace(parent.space());
            synchronized (RadianceFlywheelEngine.this) {
                embeddings.add(this);
            }
        }

        private <I extends Instance> Instancer<I> instancer(
                InstanceType<I> type, Model model, int bias) {
            if (deleted)
                throw new IllegalStateException("Cannot create an instancer in deleted embedding");
            return RadianceFlywheelEngine.this.instancer(this, type, model, bias);
        }

        @Override
        public void transforms(Matrix4fc pose, Matrix3fc normal) {
            synchronized (RadianceFlywheelEngine.this) {
                space.pose(new org.joml.Matrix4d(pose), normal);
            }
        }

        private void markDependentInstancesDirty() {
            instancers.values().stream()
                    .filter(i -> i.context.dependsOn(this))
                    .forEach(RadianceInstancer::markAllDirty);
        }

        @Override
        public InstancerProvider instancerProvider() {
            return ownedProvider(provider);
        }

        @Override
        public InstancerProvider provider() {
            return provider;
        }

        @Override
        public Vec3i renderOrigin() {
            return origin;
        }

        @Override
        public Vec3i origin() {
            return origin;
        }

        @Override
        public VisualEmbedding createEmbedding(Vec3i childOrigin) {
            if (deleted) throw new IllegalStateException("Cannot nest a deleted embedding");
            return new Embedding(this, childOrigin);
        }

        @Override
        public void delete() {
            synchronized (RadianceFlywheelEngine.this) {
                deleted = true;
                markDependentInstancesDirty();
            }
        }

        @Override
        public boolean deleted() {
            return deleted || parent.deleted();
        }

        @Override
        public boolean dependsOn(Context ancestor) {
            return this == ancestor || parent.dependsOn(ancestor);
        }

        @Override
        public com.radiance.api.render.RenderSpace space() {
            return space;
        }
    }

    private final class RadianceInstancer<I extends Instance> implements Instancer<I> {
        private final InstanceType<I> type;
        private final FlywheelInstanceAdapter adapter;
        private final Model source;
        private final ModelState model;
        private final int bias;
        private final Context context;
        private final List<Handle<I>> instances = new ArrayList<>();

        /** Set under this lock and the engine lock when the engine drops this empty instancer. */
        private boolean detached;

        private RadianceInstancer(
                InstanceType<I> type,
                FlywheelInstanceAdapter adapter,
                Model source,
                ModelState model,
                int bias,
                Context context) {
            this.type = type;
            this.adapter = adapter;
            this.source = source;
            this.model = model;
            this.bias = bias;
            this.context = context;
        }

        @Override
        public I createInstance() {
            return createOwned(null);
        }

        private I createOwned(FlywheelHandoffs.Owner sourceOwner) {
            synchronized (this) {
                if (!detached) {
                    if (context.deleted()) throw new IllegalStateException("Embedding is deleted");
                    Handle<I> handle = new Handle<>(instanceIds.getAndIncrement(), this);
                    I instance = type.create(handle);
                    handle.instance =
                            Objects.requireNonNull(instance, "InstanceType.create returned null");
                    handle.sourceOwner(sourceOwner);
                    instances.add(handle);
                    pendingInstances.add(handle);
                    return instance;
                }
            }
            // Not under this lock: the engine lock is always taken before an instancer lock.
            return live().createOwned(sourceOwner);
        }

        /** The engine's current instancer for this key; recreates it (and its model) if dropped. */
        private RadianceInstancer<I> live() {
            return (RadianceInstancer<I>)
                    RadianceFlywheelEngine.this.instancer(context, type, source, bias);
        }

        private synchronized boolean detachIfEmpty() {
            if (!instances.isEmpty()) return false;
            detached = true;
            return true;
        }

        @Override
        public void stealInstance(I instance) {
            if (instance == null) return;
            if (!(instance.handle() instanceof Handle<?> raw)) {
                throw new IllegalArgumentException("Instance belongs to another backend");
            }
            @SuppressWarnings("unchecked")
            Handle<I> handle = (Handle<I>) raw;
            synchronized (INSTANCE_TRANSFER_LOCK) {
                RadianceFlywheelEngine oldEngine = handle.engine;
                RadianceFlywheelEngine newEngine = RadianceFlywheelEngine.this;
                synchronized (oldEngine) {
                    synchronized (newEngine) {
                        if (handle.owner == this) return;
                        // Detaching holds this engine's lock, so the flag is stable here.
                        if (detached) {
                            live().stealInstance(instance);
                            return;
                        }
                        if (handle.owner.type != this.type) {
                            throw new IllegalArgumentException(
                                    "Cannot steal a Flywheel instance into a different InstanceType");
                        }
                        oldEngine.ensureLive();
                        newEngine.ensureLive();
                        handle.owner.instances.remove(handle);
                        if (oldEngine == newEngine) {
                            handle.owner = this;
                            handle.setChanged();
                            handle.stagedReady = false;
                            instances.add(handle);
                            return;
                        }
                        if (handle.nativeCreated) {
                            oldEngine.nativeEngine.remove(handle.id);
                            handle.nativeCreated = false;
                        }
                        handle.engine = newEngine;
                        handle.id = newEngine.instanceIds.getAndIncrement();
                        handle.owner = this;
                        handle.setChanged();
                        handle.stagedReady = false;
                        instances.add(handle);
                    }
                }
            }
        }

        private synchronized void markAllDirty() {
            instances.forEach(Handle::setChanged);
        }

        private synchronized void deleteAll() {
            detached = true;
            for (Handle<I> handle : instances) {
                if (handle.nativeCreated) {
                    handle.engine.nativeEngine.remove(handle.id);
                    handle.nativeCreated = false;
                }
                handle.deleted = true;
                handle.sourceOwner(null);
            }
            instances.clear();
        }
    }

    private final class Handle<I extends Instance>
            implements InstanceHandle, FlywheelHandoffs.Member {
        private long id;
        private RadianceFlywheelEngine engine = RadianceFlywheelEngine.this;
        private RadianceInstancer<I> owner;
        private I instance;
        private volatile boolean dirty = true;
        private final AtomicLong revision = new AtomicLong();
        private volatile boolean deleted;
        private volatile boolean visible = true;
        private boolean nativeCreated;
        private boolean stagedReady;
        private boolean retentionCounted;
        private FlywheelHandoffs.Owner sourceOwner;

        private void sourceOwner(FlywheelHandoffs.Owner next) {
            if (sourceOwner == next) return;
            if (sourceOwner != null) sourceOwner.remove(this);
            sourceOwner = next;
            if (next != null) next.add(this);
        }

        public boolean prepared() {
            synchronized (engine) {
                return deleted
                        || (owner.model.uploaded
                                && (stagedReady
                                        || (nativeCreated
                                                && (sourceOwner == null || !sourceOwner.stage()))));
            }
        }

        public boolean present() {
            return nativeCreated;
        }

        public void publish() {
            synchronized (engine) {
                if (!engine.deleted) {
                    flush();
                    engine.pendingInstances.add(this);
                }
            }
        }

        public void cancel() {
            synchronized (engine) {
                if (!engine.deleted && (deleted || owner.context.deleted())) flush();
                stagedReady = false;
                setChanged();
                engine.pendingInstances.add(this);
            }
        }

        private void flush() {
            if (deleted || owner.context.deleted()) {
                deleted = true;
                if (nativeCreated
                        && sourceOwner != null
                        && sourceOwner.retain()
                        && !retentionCounted) {
                    retentionCounted = true;
                    FlywheelHandoffs.retainedInstance();
                }
                if (nativeCreated && (sourceOwner == null || !sourceOwner.retain())) {
                    engine.nativeEngine.remove(id);
                    nativeCreated = false;
                }
            } else if (dirty) {
                if (sourceOwner != null && sourceOwner.stage()) {
                    if (!stagedReady) FlywheelHandoffs.stagedInstance();
                    stagedReady = true;
                    return;
                }
                long before = revision.get();
                write();
                if (revision.get() != before) setChanged();
            } else if (nativeCreated && sourceOwner != null && sourceOwner.stage()) {
                stagedReady = true;
            }
        }

        private Handle(long id, RadianceInstancer<I> owner) {
            this.id = id;
            this.owner = owner;
        }

        @Override
        public void setChanged() {
            if (!deleted) {
                revision.incrementAndGet();
                dirty = true;
                engine.pendingInstances.add(this);
            }
        }

        @Override
        public void setDeleted() {
            deleted = true;
            dirty = true;
            engine.pendingInstances.add(this);
        }

        @Override
        public void setVisible(boolean visible) {
            if (this.visible != visible) {
                this.visible = visible;
                setChanged();
            }
        }

        @Override
        public boolean isVisible() {
            return visible && !deleted;
        }

        private void write() {
            if (SOURCE_AUDIT) INSTANCE_WRITES.increment();
            int size = owner.type.layout().byteSize();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.calloc(size);
                owner.type.writer().write(MemoryUtil.memAddress(data), instance);
                var canonical =
                        FlywheelInstanceDecoder.decode(
                                owner.adapter,
                                data,
                                stack.malloc(
                                        com.radiance.render.contract.RenderAbi
                                                .CANONICAL_INSTANCE_BYTES));
                engine.nativeEngine.update(
                        id,
                        owner.model.nativeModel,
                        owner.bias,
                        visible,
                        canonical,
                        owner.context instanceof Embedding,
                        owner.context.space());
                nativeCreated = true;
                dirty = false;
            }
        }
    }

    private static final class ModelState implements AutoCloseable {
        private final List<MeshState> meshes;
        private int nativeModel;
        private boolean uploaded;
        private boolean released;

        private ModelState(List<MeshState> meshes) {
            this.meshes = meshes;
        }

        private static ModelState capture(Model model) {
            if (SOURCE_AUDIT) MODEL_CAPTURES.increment();
            List<MeshState> meshes = new ArrayList<>(model.meshes().size());
            try {
                for (Model.ConfiguredMesh configured : model.meshes()) {
                    meshes.add(
                            new MeshState(
                                    FlywheelMeshData.capture(configured.mesh()),
                                    FlywheelMaterialData.capture(configured.material())));
                }
                return new ModelState(meshes);
            } catch (Throwable failure) {
                meshes.forEach(MeshState::close);
                throw failure;
            }
        }

        private synchronized void upload(InstanceScene engine) {
            if (uploaded) return;
            if (released) throw new IllegalStateException("Flywheel model was already released");
            if (SOURCE_AUDIT) MODEL_UPLOADS.increment();
            nativeModel = engine.createModel(meshes.size());
            if (nativeModel <= 0)
                throw new IllegalStateException("Native Flywheel model was not allocated");
            try {
                for (int i = 0; i < meshes.size(); i++) {
                    MeshState mesh = meshes.get(i);
                    FlywheelMaterialData material = mesh.material;
                    engine.uploadMesh(
                            nativeModel,
                            i,
                            mesh.mesh.vertices(),
                            mesh.mesh.indices(),
                            material.textureId(),
                            material.alphaMode(),
                            material.flags(),
                            material.key());
                }
                engine.finishModel(nativeModel);
                uploaded = true;
            } finally {
                if (uploaded) meshes.forEach(MeshState::close);
            }
        }

        /** Only once no instance uses the model; the native side retires it with the frame. */
        private synchronized void release(InstanceScene engine) {
            if (released) return;
            released = true;
            meshes.forEach(MeshState::close);
            if (uploaded) engine.deleteModel(nativeModel);
        }

        @Override
        public synchronized void close() {
            meshes.forEach(MeshState::close);
        }
    }

    private record MeshState(FlywheelMeshData mesh, FlywheelMaterialData material)
            implements AutoCloseable {
        @Override
        public void close() {
            mesh.close();
        }
    }

    private static final class InstancerKey {
        private final Context context;
        private final InstanceType<?> type;
        private final Model model;
        private final int bias;

        private InstancerKey(Context context, InstanceType<?> type, Model model, int bias) {
            this.context = context;
            this.type = type;
            this.model = model;
            this.bias = bias;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof InstancerKey other
                    && context == other.context
                    && type == other.type
                    && model == other.model
                    && bias == other.bias;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(context);
            result = 31 * result + System.identityHashCode(type);
            result = 31 * result + System.identityHashCode(model);
            return 31 * result + bias;
        }
    }
}
