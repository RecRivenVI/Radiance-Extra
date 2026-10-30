package com.radiance.extra.compatibility.aeronautics;

import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.api.render.*;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.texture.TextureTasks;
import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import com.radiance.extra.mixins.compatibility.aeronautics.LevititeMotionAccessor;
import com.radiance.render.contract.DrawStateSnapshot;
import com.radiance.render.contract.RenderAbi;
import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import dev.eriksonn.aeronautics.index.AeroBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.io.IOException;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Source shader interpretation. The world renderer only receives meshes, fields and colours. */
public final class LevititeWorldAdapter {
    private record Key(Object owner, RenderType layer) {}

    private static final Map<Key, Mesh> MESHES = new HashMap<>();
    private static final int BODY_TYPE =
            RenderAbi.GEOMETRY_WORLD_SOLID
                    | (RenderAbi.FACE_BACK_BIT << RenderAbi.FACE_GEOMETRY_SHIFT);
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static ClientLevel world;
    private static RenderResources.Texture noise;
    private static ComputedSurface program, emptyContacts;
    private static long epoch;
    private static long bodies, ghosts, definitions;

    private LevititeWorldAdapter() {}

    public static boolean owns(RenderType layer) {
        return LevititeLayers.owns(layer);
    }

    public static long[] counters() {
        return new long[] {bodies, ghosts, definitions, MESHES.size()};
    }

    public static void collect(ClientLevel level, float partial) {
        if (world != level || epoch != RenderPlatform.resourceEpoch()) {
            close();
            world = level;
            epoch = RenderPlatform.resourceEpoch();
        }
        var seen = new HashSet<Key>();
        var snapshots = SectionMeshes.retained();
        for (var snapshot : snapshots) {
            if (!snapshot.layer().name.equals("aeronautics:levitite")) continue;
            var key = new Key(snapshot.section(), snapshot.layer());
            seen.add(key);
            Mesh mesh = MESHES.get(key);
            if (mesh == null
                    || (mesh.revision != snapshot.revision()
                            && !mesh.acceptPublication(snapshot))) {
                Mesh previous = mesh;
                mesh =
                        new Mesh(
                                snapshot.revision(),
                                snapshot.origin(),
                                snapshot.layer(),
                                snapshot.vertices(),
                                snapshot.count());
                MESHES.put(key, mesh);
                if (previous != null) previous.close();
            }
        }
        MESHES.entrySet()
                .removeIf(
                        e -> {
                            if (seen.contains(e.getKey())) return false;
                            e.getValue().close();
                            return true;
                        });
        if (MESHES.isEmpty()) return;
        var mc = Minecraft.getInstance();
        TextureTasks.Owner atlas =
                TextureProxy.TASKS.owner(
                        mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
        if (atlas == null) return;
        float time = level.getGameTime() % 100000 + partial;
        try (var stage =
                WorldScene.enterStage(
                        WorldScene.StageKey.AFTER_SOLID_BLOCKS,
                        WorldScene.CoordinateSpace.WORLD,
                        Vec3.ZERO,
                        WorldScene.TargetKind.DEFAULT_WORLD,
                        "radiance_extra:levitite")) {
            if (program == null) initializeCompute();
            Map<ClientSubLevel, State> states = new IdentityHashMap<>();
            State stationary = state(null, partial);
            for (var mesh : MESHES.values()) {
                var containing = Sable.HELPER.getContaining(level, mesh.origin);
                ClientSubLevel sub = containing instanceof ClientSubLevel c ? c : null;
                State state =
                        sub == null
                                ? stationary
                                : states.computeIfAbsent(sub, c -> state(c, partial));
                if (state == null) continue;
                mesh.draw(state, atlas, time, level);
            }
            stage.commit();
        }
    }

    private record State(
            ClientSubLevel source,
            SableSceneRegistry.SharedSpace parent,
            Vector3d centre,
            Vector3d rotationPoint,
            org.joml.Quaterniond rotation,
            Vector3d scale,
            LevititeMotion motion) {}

    private static State state(ClientSubLevel source, float partial) {
        Vector3f linear = new Vector3f(), angular = new Vector3f(), gravity = new Vector3f();
        Vector3d centre = new Vector3d(), point = new Vector3d(), scale = new Vector3d(1);
        var rotation = new org.joml.Quaterniond();
        SableSceneRegistry.SharedSpace parent = null;
        boolean moving = false;
        if (source != null) {
            parent = SableSceneRegistry.sharedSpace(source);
            if (parent == null) return null;
            var pose = source.renderPose(partial);
            centre.set(pose.position());
            point.set(pose.rotationPoint());
            rotation.set(pose.orientation());
            scale.set(pose.scale());
            var manager =
                    LevititeShaderManager.managers.computeIfAbsent(
                            source, k -> new LevititeShaderManager());
            var access = (LevititeMotionAccessor) manager;
            var l =
                    access.radiance$previousLinear()
                            .lerp(access.radiance$linear(), partial, new Vector3d());
            var a =
                    access.radiance$previousAngular()
                            .lerp(access.radiance$angular(), partial, new Vector3d());
            moving =
                    access.radiance$linear().lengthSquared() > 1e-6
                            || access.radiance$angular().lengthSquared() > 1e-6;
            rotation.transformInverse(l);
            rotation.transformInverse(a);
            linear.set(l.mul(20));
            angular.set(a.mul(20));
            var g =
                    DimensionPhysicsData.getGravity(
                            source.getLevel(), source.logicalPose().position(), new Vector3d());
            rotation.transformInverse(g);
            gravity.set(g);
            moving &= g.lengthSquared() > .001;
        }
        if (source == null) {
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            point.set(
                    camera.x - camera.x % 10000,
                    camera.y - camera.y % 10000,
                    camera.z - camera.z % 10000);
        }
        var material =
                PhysicsBlockPropertyHelper.getFloatingMaterial(
                        AeroBlocks.LEVITITE.getDefaultState());
        if (material == null || !(material.transitionSpeed() > 0)) return null;
        return new State(
                source,
                parent,
                centre,
                point,
                rotation,
                scale,
                new LevititeMotion(
                        linear,
                        angular,
                        gravity,
                        (float) material.transitionSpeed(),
                        LevititeMotion.dragMatrix(
                                gravity,
                                (float) material.slowVerticalFriction(),
                                (float) material.slowHorizontalFriction()),
                        LevititeMotion.dragMatrix(
                                gravity,
                                (float) material.fastVerticalFriction(),
                                (float) material.fastHorizontalFriction()),
                        moving));
    }

    private static void initializeCompute() {
        try (var stream =
                LevititeWorldAdapter.class.getResourceAsStream(
                        "/assets/radiance_extra/shaders/world/levitite.comp.spv")) {
            if (stream == null) throw new IllegalStateException("Missing Levitite compute program");
            program = ComputedSurface.program(stream.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Levitite compute program", e);
        }
        var path =
                ResourceLocation.fromNamespaceAndPath(
                        "aeronautics", "textures/special/noise_composed.png");
        var resource = Minecraft.getInstance().getResourceManager().getResource(path).orElseThrow();
        try (var stream = resource.open();
                var image = NativeImage.read(stream)) {
            boolean blur =
                    resource.metadata()
                            .getSection(
                                    net.minecraft.client.resources.metadata.texture
                                            .TextureMetadataSection.SERIALIZER)
                            .map(
                                    net.minecraft.client.resources.metadata.texture
                                                    .TextureMetadataSection
                                            ::isBlur)
                            .orElse(false);
            noise =
                    RenderResources.rgba8(
                            image.getWidth(),
                            image.getHeight(),
                            1,
                            blur
                                    ? RenderResources.Sampling.LINEAR
                                    : RenderResources.Sampling.NEAREST,
                            RenderResources.Sampling.NEAREST);
            TextureProxy.setClamp(noise.id(), 0);
            ByteBuffer pixels =
                    MemoryUtil.memAlloc(image.getWidth() * image.getHeight() * 4)
                            .order(ByteOrder.nativeOrder());
            try {
                for (int y = 0; y < image.getHeight(); y++)
                    for (int x = 0; x < image.getWidth(); x++)
                        pixels.putInt((y * image.getWidth() + x) * 4, image.getPixelRGBA(x, y));
                noise.upload(0, 0, 0, image.getWidth(), image.getHeight(), pixels);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Levitite source noise", e);
        }
        ByteBuffer empty = MemoryUtil.memCalloc(64);
        try {
            emptyContacts = ComputedSurface.data(empty);
        } finally {
            MemoryUtil.memFree(empty);
        }
    }

    private static final class Mesh implements AutoCloseable {
        Object revision;
        ByteBuffer definition;
        final BlockPos origin;
        final int count, width = 256, height;
        final ByteBuffer geometry,
                parameters = MemoryUtil.memCalloc(256).order(ByteOrder.nativeOrder());
        ByteBuffer input;
        ComputedSurface source;
        final RenderResources.Texture field;
        final LayeredWorldMaterial body = new LayeredWorldMaterial();
        LayeredWorldMaterial[] shells;
        final LocalModelBatch batch = new LocalModelBatch(1);
        final long identity = RenderResources.geometryIdentity();
        final long history = Long.MIN_VALUE | (1L << 59) | identity;
        final int[] dynamicIds = {
            (int) RenderResources.geometryIdentity(), (int) RenderResources.geometryIdentity()
        };
        final LevititeContacts contacts = new LevititeContacts();
        final float[] slow = new float[9], fast = new float[9];
        final AABB bounds;
        boolean prepared;

        Mesh(Object revision, BlockPos origin, RenderType layer, ByteBuffer input, int vertices) {
            if (vertices <= 0
                    || vertices % 4 != 0
                    || input.remaining() != vertices * 32
                    || vertices / 4 > 524287)
                throw new IllegalArgumentException("Levitite quad ABI");
            this.revision = revision;
            definition = input.asReadOnlyBuffer();
            this.origin = origin.immutable();
            count = vertices;
            height = Math.max(1, (vertices / 4 * 25 * 2 + width - 1) / width);
            geometry = MemoryUtil.memCalloc(vertices * 128).order(ByteOrder.nativeOrder());
            this.input = MemoryUtil.memAlloc(input.remaining()).order(ByteOrder.nativeOrder());
            this.input.put(input.duplicate()).flip();
            field = RenderResources.scalarField(width, height);
            double minX = Double.POSITIVE_INFINITY,
                    minY = minX,
                    minZ = minX,
                    maxX = -minX,
                    maxY = -minX,
                    maxZ = -minX;
            for (int i = 0; i < vertices; i++) {
                int at = i * 128, p = i * 32;
                float x = input.getFloat(p), y = input.getFloat(p + 4), z = input.getFloat(p + 8);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                geometry.putFloat(at, x)
                        .putFloat(at + 4, y)
                        .putFloat(at + 8, z)
                        .putInt(at + 12, 1)
                        .putInt(at + 28, 1)
                        .putInt(at + 48, 1);
                for (int c = 0; c < 4; c++)
                    geometry.putFloat(at + 32 + c * 4, (input.get(p + 12 + c) & 255) / 255f);
                geometry.putFloat(at + 56, input.getFloat(p + 16))
                        .putFloat(at + 60, input.getFloat(p + 20));
                int corner = i % 4;
                geometry.putFloat(at + 80, corner == 1 || corner == 2 ? 1 : 0)
                        .putFloat(at + 84, corner >= 2 ? 1 : 0);
            }
            bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            definitions++;
        }

        boolean acceptPublication(SectionMeshes.Mesh publication) {
            // A section revision also changes when an unrelated ordinary block is edited.
            // Compare only at that boundary, retaining immutable source bytes, not GPU output.
            if (count != publication.count()
                    || !origin.equals(publication.origin())
                    || !definition.equals(publication.vertices())) return false;
            definition = publication.vertices().asReadOnlyBuffer();
            revision = publication.revision();
            return true;
        }

        void draw(State state, TextureTasks.Owner atlas, float time, ClientLevel level) {
            if (source == null) {
                source = ComputedSurface.data(input);
                MemoryUtil.memFree(input);
                input = null;
            }
            body.colourField(atlas, field.owner(), 0, 1, 1, .25f, -.45f, .8f, .3f * .2f)
                    .quadColourField();
            if (!body.publish()) return;
            if (!prepared) {
                for (int i = 0; i < count; i++) {
                    int at = i * 128, q = i / 4 * 4 * 128;
                    Vector3f a = position(q), b = position(q + 128), d = position(q + 3 * 128);
                    var n = b.sub(a).cross(d.sub(a));
                    if (n.lengthSquared() > 1e-16f) n.normalize();
                    else n.set(0, 1, 0);
                    geometry.putFloat(at + 16, n.x)
                            .putFloat(at + 20, n.y)
                            .putFloat(at + 24, n.z)
                            .putInt(at + 76, atlas.id())
                            .putInt(
                                    at + 88,
                                    RenderAbi.COLOUR_FIELD_MATERIAL_BIT
                                            | ((i / 4) << 12)
                                            | body.owner().id());
                }
                prepared = true;
            }
            parameters(state, atlas, time, body.owner().id(), 0);
            program.submit(
                    source,
                    emptyContacts,
                    parameters,
                    noise.owner(),
                    field.owner(),
                    count / 4 * 64,
                    0,
                    0,
                    0,
                    Vec3.ZERO,
                    atlas,
                    body.owner());
            batch.reset();
            batch.source(this);
            double x = origin.getX(), y = origin.getY(), z = origin.getZ();
            if (state.parent != null) {
                x -= state.parent.origin().x;
                y -= state.parent.origin().y;
                z -= state.parent.origin().z;
            }
            long model = identity << 32 | BODY_TYPE;
            var space = state.parent == null ? null : state.parent.space();
            if (!batch.selectUnchanged(
                    history,
                    this,
                    model,
                    BODY_TYPE,
                    atlas,
                    count,
                    x,
                    y,
                    z,
                    RenderAbi.QUERY_WORLD,
                    IDENTITY,
                    "default",
                    DrawStateSnapshot.IDENTITY,
                    space))
                batch.add(
                        model,
                        history,
                        BODY_TYPE,
                        atlas,
                        geometry,
                        count,
                        x,
                        y,
                        z,
                        RenderAbi.QUERY_WORLD,
                        IDENTITY,
                        "default",
                        DrawStateSnapshot.IDENTITY,
                        space);
            batch.submit();
            bodies++;
            com.radiance.api.audit.DrawingAudit.consumed("aeronautics.levitite.body");
            if (state.source == null || !state.motion.moving()) return;
            if (shells == null)
                shells =
                        new LayeredWorldMaterial[] {
                            new LayeredWorldMaterial(), new LayeredWorldMaterial()
                        };
            var plotBounds = bounds.move(origin).inflate(1.5);
            double minX = Double.POSITIVE_INFINITY,
                    minY = minX,
                    minZ = minX,
                    maxX = -minX,
                    maxY = -minX,
                    maxZ = -minX;
            for (int c = 0; c < 8; c++) {
                var p =
                        new Vector3d(
                                        (c & 1) == 0 ? bounds.minX : bounds.maxX,
                                        (c & 2) == 0 ? bounds.minY : bounds.maxY,
                                        (c & 4) == 0 ? bounds.minZ : bounds.maxZ)
                                .add(
                                        origin.getX() - state.rotationPoint.x,
                                        origin.getY() - state.rotationPoint.y,
                                        origin.getZ() - state.rotationPoint.z)
                                .mul(state.scale);
                state.rotation.transform(p).add(state.centre);
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
            var worldBounds =
                    new AABB(minX, minY, minZ, maxX, maxY, maxZ)
                            .inflate(
                                    1.5
                                            * Math.max(
                                                    state.scale.x,
                                                    Math.max(state.scale.y, state.scale.z)));
            var contactData = contacts.update(level, plotBounds, worldBounds);
            for (int layer = 0; layer < 2; layer++) {
                int sign = layer == 0 ? 1 : -1;
                var material =
                        shells[layer].colourField(
                                atlas, null, sign > 0 ? .25f : -.45f, 1, .8f, .25f, -.45f, .8f, 0);
                if (!material.publish()) continue;
                parameters(state, atlas, time, material.owner().id(), sign);
                contacts.origins(parameters, state.rotationPoint, state.centre);
                program.submit(
                        source,
                        contactData,
                        parameters,
                        noise.owner(),
                        field.owner(),
                        count / 4 * 64,
                        count * 16,
                        count / 4 * 96,
                        dynamicIds[layer],
                        new Vec3(state.centre.x, state.centre.y, state.centre.z),
                        atlas,
                        material.owner());
                ghosts++;
                com.radiance.api.audit.DrawingAudit.consumed("aeronautics.levitite.ghost");
            }
        }

        private Vector3f position(int at) {
            return new Vector3f(
                    geometry.getFloat(at), geometry.getFloat(at + 4), geometry.getFloat(at + 8));
        }

        private void parameters(
                State s, TextureTasks.Owner atlas, float time, int material, int sign) {
            put(
                    0,
                    (float) (origin.getX() - s.rotationPoint.x),
                    (float) (origin.getY() - s.rotationPoint.y),
                    (float) (origin.getZ() - s.rotationPoint.z),
                    time);
            put(
                    16,
                    (float) (s.centre.x % 10000),
                    (float) (s.centre.y % 10000),
                    (float) (s.centre.z % 10000),
                    s.source == null ? 0 : 1);
            put(
                    32,
                    s.motion.linear().x,
                    s.motion.linear().y,
                    s.motion.linear().z,
                    s.motion.transition());
            put(
                    48,
                    s.motion.angular().x,
                    s.motion.angular().y,
                    s.motion.angular().z,
                    1 - (float) Math.exp(-s.motion.gravity().length() / 3));
            put(64, s.motion.gravity().x, s.motion.gravity().y, s.motion.gravity().z, sign);
            put(80, (float) s.scale.x, (float) s.scale.y, (float) s.scale.z, 0);
            put(
                    96,
                    (float) s.rotation.x,
                    (float) s.rotation.y,
                    (float) s.rotation.z,
                    (float) s.rotation.w);
            s.motion.slow().get(slow);
            s.motion.fast().get(fast);
            for (int col = 0; col < 3; col++) {
                put(112 + col * 16, slow[col * 3], slow[col * 3 + 1], slow[col * 3 + 2], 0);
                put(160 + col * 16, fast[col * 3], fast[col * 3 + 1], fast[col * 3 + 2], 0);
            }
            put(
                    208,
                    width,
                    height,
                    Float.intBitsToFloat(atlas.id()),
                    Float.intBitsToFloat(material));
        }

        private void put(int at, float x, float y, float z, float w) {
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(z)
                    || !Float.isFinite(w))
                throw new IllegalStateException("Non-finite Levitite parameters at " + origin);
            parameters.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
        }

        public void close() {
            PersistentInstances.removeOwner(this);
            batch.close();
            body.close();
            if (shells != null) for (var shell : shells) shell.close();
            if (source != null) source.close();
            contacts.close();
            field.close();
            if (input != null) MemoryUtil.memFree(input);
            MemoryUtil.memFree(geometry);
            MemoryUtil.memFree(parameters);
        }
    }

    public static void close() {
        MESHES.values().forEach(Mesh::close);
        MESHES.clear();
        if (program != null) program.close();
        if (emptyContacts != null) emptyContacts.close();
        if (noise != null) noise.close();
        program = null;
        emptyContacts = null;
        noise = null;
        world = null;
        LevititeContacts.reset();
    }
}
