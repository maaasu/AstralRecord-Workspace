const SECTION = 16;
const VERTICAL_FOV = Math.PI / 3;
const FACES = [
    {axis: 0, step: -1, shade: 0.81, vertices: [0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0]},
    {axis: 0, step:  1, shade: 0.92, vertices: [1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1]},
    {axis: 1, step: -1, shade: 0.66, vertices: [0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1]},
    {axis: 1, step:  1, shade: 1.05, vertices: [0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0]},
    {axis: 2, step: -1, shade: 0.85, vertices: [1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0]},
    {axis: 2, step:  1, shade: 0.94, vertices: [0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1]},
];
const CULL_FACES = Object.fromEntries(
    ["west", "east", "down", "up", "north", "south"].map((name, index) => [name, FACES[index]]),
);
const ATLAS_SIZE = 2048;
// Vanilla 1.21.11 has 16- and 32-pixel-wide block sprites.
const TEXTURE_SIZE = 32;
const TILE = TEXTURE_SIZE + 2;
const ATLAS_COLUMNS = Math.floor(ATLAS_SIZE / TILE);
const MOVEMENT_KEYS = new Set(["w", "a", "s", "d", "q", "e"]);

const vertexSource = `#version 300 es
in vec3 aPosition;
in vec4 aColor;
in vec2 aUv;
uniform mat4 uProjection;
uniform mat4 uView;
out vec3 vPosition;
out vec4 vColor;
out vec2 vUv;
void main() {
    vPosition = aPosition;
    vColor = aColor;
    vUv = aUv;
    gl_Position = uProjection * uView * vec4(aPosition, 1.0);
}
`;

const fragmentSource = `#version 300 es
precision highp float;
in vec3 vPosition;
in vec4 vColor;
in vec2 vUv;
uniform sampler2D uAtlas;
uniform float uAlphaCutoff;
out vec4 outColor;
void main() {
    if (vUv.x >= 0.0) {
        vec4 sampleColor = texture(uAtlas, vUv);
        if (sampleColor.a < uAlphaCutoff) discard;
        outColor = sampleColor * vColor;
        return;
    }
    vec3 normal = abs(cross(dFdx(vPosition), dFdy(vPosition)));
    vec2 plane = normal.x > max(normal.y, normal.z) ? vPosition.zy
        : (normal.y > normal.z ? vPosition.xz : vPosition.xy);
    vec2 tile = fract(plane);
    vec2 edgeDistance = min(tile, 1.0 - tile);
    float sampleIndex = floor(tile.x * 16.0) + floor(tile.y * 16.0) * 17.0;
    float detail = fract(sampleIndex * (sampleIndex * 0.011 + 0.127));
    float fine = 1.0 - smoothstep(0.07, 0.27, max(fwidth(plane.x), fwidth(plane.y)));
    float border = smoothstep(0.0, 0.05, min(edgeDistance.x, edgeDistance.y));
    float lighting = 1.0 + (detail - 0.5) * 0.065 * fine - (1.0 - border) * 0.10 * fine;
    outColor = vec4(vColor.rgb * lighting, vColor.a);
}
`;

function compileShader(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
        const reason = gl.getShaderInfoLog(shader);
        gl.deleteShader(shader);
        throw new Error(`3D描画の準備に失敗しました。 ${reason}`);
    }
    return shader;
}

function createProgram(gl) {
    const vertex = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
    const fragment = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
    const program = gl.createProgram();
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.linkProgram(program);
    gl.deleteShader(vertex);
    gl.deleteShader(fragment);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        const reason = gl.getProgramInfoLog(program);
        gl.deleteProgram(program);
        throw new Error(`3D描画の準備に失敗しました。 ${reason}`);
    }
    return {
        program,
        position: gl.getAttribLocation(program, "aPosition"),
        color: gl.getAttribLocation(program, "aColor"),
        uv: gl.getAttribLocation(program, "aUv"),
        atlas: gl.getUniformLocation(program, "uAtlas"),
        alphaCutoff: gl.getUniformLocation(program, "uAlphaCutoff"),
        projection: gl.getUniformLocation(program, "uProjection"),
        view: gl.getUniformLocation(program, "uView"),
    };
}

function createTextureAtlas(gl, changed) {
    const texture = gl.createTexture();
    const images = new AbortController();
    const loaded = new Set();
    const tile = document.createElement("canvas");
    tile.width = tile.height = TILE;
    const context = tile.getContext("2d");
    context.imageSmoothingEnabled = false;
    let allocated = false;

    function bind() {
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texture);
    }

    bind();
    for (const parameter of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) {
        gl.texParameteri(gl.TEXTURE_2D, parameter, gl.NEAREST);
    }
    for (const parameter of [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T]) {
        gl.texParameteri(gl.TEXTURE_2D, parameter, gl.CLAMP_TO_EDGE);
    }
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE,
        Uint8Array.of(255, 255, 255, 255));

    function upload(x, y) {
        bind();
        gl.texSubImage2D(gl.TEXTURE_2D, 0, x, y, gl.RGBA, gl.UNSIGNED_BYTE, tile);
    }

    async function load(id, x, y) {
        try {
            const response = await fetch(`/api/textures/${id}.png`, {signal: images.signal});
            if (!response.ok) return;
            const image = await createImageBitmap(await response.blob());
            try {
                if (images.signal.aborted) return;
                context.clearRect(0, 0, TILE, TILE);
                // The first square in a vertically animated vanilla texture is its preview frame.
                const frameHeight = Math.min(image.width, image.height);
                context.drawImage(image, 0, 0, image.width, frameHeight, 1, 1, TEXTURE_SIZE, TEXTURE_SIZE);
                // Repeat the last texel at each edge so UVs at 0 or 1 stay within this sprite.
                context.drawImage(tile, 1, 1, TEXTURE_SIZE, 1, 1, 0, TEXTURE_SIZE, 1);
                context.drawImage(tile, 1, TEXTURE_SIZE, TEXTURE_SIZE, 1, 1, TILE - 1, TEXTURE_SIZE, 1);
                context.drawImage(tile, 1, 0, 1, TILE, 0, 0, 1, TILE);
                context.drawImage(tile, TEXTURE_SIZE, 0, 1, TILE, TILE - 1, 0, 1, TILE);
                upload(x, y);
                changed();
            } finally {
                image.close();
            }
        } catch {
            // An unreadable texture keeps its visible checkerboard tile.
        }
    }

    function textureOrigin(id) {
        if (!Number.isInteger(id) || id < 0 || id >= ATLAS_COLUMNS ** 2) return null;
        const x = (id % ATLAS_COLUMNS) * TILE;
        const y = Math.floor(id / ATLAS_COLUMNS) * TILE;
        if (!loaded.has(id)) {
            if (!allocated) {
                bind();
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, ATLAS_SIZE, ATLAS_SIZE, 0,
                    gl.RGBA, gl.UNSIGNED_BYTE, null);
                allocated = true;
            }
            context.fillStyle = "#a428a0";
            context.fillRect(0, 0, TILE, TILE);
            context.fillStyle = "#39273f";
            context.fillRect(0, 0, TILE / 2, TILE / 2);
            context.fillRect(TILE / 2, TILE / 2, TILE / 2, TILE / 2);
            upload(x, y);
            loaded.add(id);
            void load(id, x, y);
        }
        return [x + 1, y + 1];
    }

    return {
        bind,
        textureOrigin,
        dispose() {
            images.abort();
            gl.deleteTexture(texture);
        },
    };
}

function isFullCube(model) {
    if (model.faces) {
        return model.occludes || (
            model.faces.length === 6 && model.faces.every(face =>
                face.cull != null && face.vertices.every(vertex => vertex.every(value => value === 0 || value === 1)))
        );
    }
    if (model.boxes.length !== 1) return false;
    const [x0, y0, z0, x1, y1, z1] = model.boxes[0];
    return x0 === 0 && y0 === 0 && z0 === 0 && x1 === 1 && y1 === 1 && z1 === 1;
}

function faceLight(face) {
    if (!face.shade) return 1;
    const [a, b, c] = face.vertices;
    const [ux, uy, uz] = b.map((value, axis) => value - a[axis]);
    const [vx, vy, vz] = c.map((value, axis) => value - a[axis]);
    const nx = uy * vz - uz * vy;
    const ny = uz * vx - ux * vz;
    const nz = ux * vy - uy * vx;
    const size = nx * nx + ny * ny + nz * nz;
    if (!size) return 1;
    return (0.6 * nx * nx + (ny >= 0 ? 1 : 0.5) * ny * ny + 0.8 * nz * nz) / size;
}

function prepareModel(model, atlas) {
    const fullCube = isFullCube(model);
    if (!model.faces) {
        return {
            ...model,
            fullCube,
            occludes: fullCube && model.opacity === 1,
            translucent: model.opacity < 1,
            visible: model.boxes.length > 0,
        };
    }
    return {
        fullCube,
        occludes: model.occludes,
        translucent: model.layer === "translucent",
        visible: model.faces.length > 0,
        faces: model.faces.map(face => {
            const origin = atlas.textureOrigin(face.texture);
            const uvs = origin && face.uv.map(([u, v]) => [
                (origin[0] + clamp(u, 0, 1) * TEXTURE_SIZE) / ATLAS_SIZE,
                (origin[1] + clamp(v, 0, 1) * TEXTURE_SIZE) / ATLAS_SIZE,
            ]);
            const light = faceLight(face);
            const tint = uvs ? face.tint ?? [255, 255, 255] : [165, 45, 145];
            return {
                vertices: face.vertices,
                uvs,
                direction: CULL_FACES[face.cull],
                color: [...tint.map(channel => Math.round(channel * light)), 255],
            };
        }),
    };
}

function newMesh() {
    return {positions: [], colors: [], indices: [], uvs: null};
}

function appendFace(mesh, point, model, box, face) {
    const offset = mesh.positions.length / 3;
    // Slight block-to-block variation keeps repeated fallback materials distinguishable.
    const tone = face.shade * (0.96 + ((point[0] * 7 + point[1] * 11 + point[2] * 13) % 13) / 200);
    const color = model.color.map(channel => Math.min(255, Math.round(channel * tone)));
    color.push(Math.round(model.opacity * 255));
    for (let corner = 0; corner < 12; corner += 3) {
        for (let axis = 0; axis < 3; axis++) {
            mesh.positions.push(point[axis] + box[axis + 3 * face.vertices[corner + axis]]);
        }
        mesh.colors.push(...color);
        if (mesh.uvs) mesh.uvs.push(-1, -1);
    }
    mesh.indices.push(offset, offset + 1, offset + 2, offset, offset + 2, offset + 3);
}

function appendTexturedFace(mesh, point, face) {
    const offset = mesh.positions.length / 3;
    mesh.uvs ??= new Array(offset * 2).fill(-1);
    for (let corner = 0; corner < 4; corner++) {
        const [x, y, z] = face.vertices[corner];
        mesh.positions.push(point[0] + x, point[1] + y, point[2] + z);
        mesh.colors.push(...face.color);
        const uv = face.uvs?.[corner];
        mesh.uvs.push(uv?.[0] ?? -1, uv?.[1] ?? -1);
    }
    mesh.indices.push(offset, offset + 1, offset + 2, offset, offset + 2, offset + 3);
}

function upload(gl, target, data) {
    const buffer = gl.createBuffer();
    gl.bindBuffer(target, buffer);
    gl.bufferData(target, data, gl.STATIC_DRAW);
    return buffer;
}

function uploadMesh(gl, mesh) {
    if (!mesh.indices.length) return null;
    return {
        positions: upload(gl, gl.ARRAY_BUFFER, new Float32Array(mesh.positions)),
        colors: upload(gl, gl.ARRAY_BUFFER, new Uint8Array(mesh.colors)),
        indices: upload(gl, gl.ELEMENT_ARRAY_BUFFER, new Uint32Array(mesh.indices)),
        uvs: mesh.uvs && upload(gl, gl.ARRAY_BUFFER, new Float32Array(mesh.uvs)),
        count: mesh.indices.length,
    };
}

function deleteMesh(gl, mesh) {
    if (!mesh) return;
    gl.deleteBuffer(mesh.positions);
    gl.deleteBuffer(mesh.colors);
    gl.deleteBuffer(mesh.indices);
    gl.deleteBuffer(mesh.uvs);
}

function drawMesh(gl, program, mesh) {
    if (!mesh) return;
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.positions);
    gl.vertexAttribPointer(program.position, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.colors);
    gl.vertexAttribPointer(program.color, 4, gl.UNSIGNED_BYTE, true, 0, 0);
    if (mesh.uvs) {
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.uvs);
        gl.vertexAttribPointer(program.uv, 2, gl.FLOAT, false, 0, 0);
        gl.enableVertexAttribArray(program.uv);
    } else {
        gl.disableVertexAttribArray(program.uv);
        gl.vertexAttrib2f(program.uv, -1, -1);
    }
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.indices);
    gl.drawElements(gl.TRIANGLES, mesh.count, gl.UNSIGNED_INT, 0);
}

function hiddenFace(scene, models, strides, index, id, point, face) {
    const {axis, step} = face;
    const neighbor = point[axis] + step;
    if (neighbor < 0 || neighbor >= scene.size[axis]) return false;
    const neighborId = scene.blocks[index + step * strides[axis]];
    const neighborModel = models[neighborId];
    return neighborModel && (neighborModel.occludes || (
        neighborId === id && neighborModel.fullCube && neighborModel.translucent
    ));
}

function meshSection(gl, scene, models, start) {
    const [width, height, length] = scene.size;
    const [startX, startY, startZ] = start;
    const [endX, endY, endZ] = start.map((coordinate, axis) => Math.min(scene.size[axis], coordinate + SECTION));
    const opaque = newMesh();
    const translucent = newMesh();
    const strides = [1, width * length, width];
    const point = [0, 0, 0];
    for (let y = startY; y < endY; y++) {
        point[1] = y;
        for (let z = startZ; z < endZ; z++) {
            point[2] = z;
            for (let x = startX; x < endX; x++) {
                const index = x + width * (z + length * y);
                const id = scene.blocks[index];
                const model = models[id];
                if (!model?.visible) continue;
                point[0] = x;
                const mesh = model.translucent ? translucent : opaque;
                if (model.faces) {
                    for (const face of model.faces) {
                        if (face.direction && hiddenFace(scene, models, strides, index, id, point, face.direction)) continue;
                        appendTexturedFace(mesh, point, face);
                    }
                    continue;
                }
                for (const face of FACES) {
                    const {axis, step} = face;
                    const hidden = hiddenFace(scene, models, strides, index, id, point, face);
                    for (const box of model.boxes) {
                        if (hidden && box[axis + (step > 0 ? 3 : 0)] === (step > 0 ? 1 : 0)) continue;
                        appendFace(mesh, point, model, box, face);
                    }
                }
            }
        }
    }
    return {
        opaque: uploadMesh(gl, opaque),
        translucent: uploadMesh(gl, translucent),
        center: [(startX + endX) / 2, (startY + endY) / 2, (startZ + endZ) / 2],
    };
}

function occupiedBounds(scene, models) {
    const [width, height, length] = scene.size;
    const visible = models.map(model => model.visible);
    const min = scene.size.slice();
    const max = [0, 0, 0];
    for (let y = 0; y < height; y++) {
        for (let z = 0; z < length; z++) {
            const row = width * (z + length * y);
            for (let x = 0; x < width; x++) {
                if (!visible[scene.blocks[row + x]]) continue;
                min[0] = Math.min(min[0], x);
                min[1] = Math.min(min[1], y);
                min[2] = Math.min(min[2], z);
                max[0] = Math.max(max[0], x + 1);
                max[1] = Math.max(max[1], y + 1);
                max[2] = Math.max(max[2], z + 1);
            }
        }
    }
    return max[1] > min[1] ? {min, max} : {min: [0, 0, 0], max: scene.size};
}

function perspective(aspect, far) {
    const near = 0.04;
    const f = 1 / Math.tan(VERTICAL_FOV / 2);
    return new Float32Array([
        f / aspect, 0, 0, 0,
        0, f, 0, 0,
        0, 0, (far + near) / (near - far), -1,
        0, 0, 2 * far * near / (near - far), 0,
    ]);
}

function dot(a, b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

function viewMatrix(eye, right, up, back) {
    return new Float32Array([
        right[0], up[0], back[0], 0,
        right[1], up[1], back[1], 0,
        right[2], up[2], back[2], 0,
        -dot(right, eye), -dot(up, eye), -dot(back, eye), 1,
    ]);
}

function cameraBasis(yaw, pitch) {
    const sinYaw = Math.sin(yaw);
    const cosYaw = Math.cos(yaw);
    const sinPitch = Math.sin(pitch);
    const cosPitch = Math.cos(pitch);
    return {
        right: [cosYaw, 0, -sinYaw],
        up: [-sinYaw * sinPitch, cosPitch, -cosYaw * sinPitch],
        back: [sinYaw * cosPitch, sinPitch, cosYaw * cosPitch],
    };
}

function clamp(value, low, high) {
    return Math.min(high, Math.max(low, value));
}

/** Create an interactive, read-only viewer for a mutable voxel scene. */
export function createRenderer(canvas) {
    const gl = canvas.getContext("webgl2", {alpha: false, antialias: true});
    if (!gl) throw new Error("このブラウザでは 3D表示に必要な WebGL 2 が利用できません。");

    let program = createProgram(gl);
    let atlas = createTextureAtlas(gl, schedule);
    let scene;
    let models = [];
    let sectionSize = [];
    let sceneSpan = 1;
    let target = [0, 0, 0];
    let yaw = 2.39;
    let pitch = 0.49;
    let distance = 10;
    let frame = 0;
    let lastFrame = 0;
    let drag;
    let lost = false;
    const events = new AbortController();
    const keys = new Set();
    const dirty = new Set();
    const sections = new Map();
    const resize = new ResizeObserver(schedule);
    resize.observe(canvas);

    function schedule() {
        if (!frame && !lost) frame = requestAnimationFrame(paint);
    }

    function move(seconds) {
        if (!keys.size) return;
        const speed = Math.max(3, distance * 0.33) * seconds;
        const forward = Number(keys.has("w")) - Number(keys.has("s"));
        const sideways = Number(keys.has("d")) - Number(keys.has("a"));
        target[0] += speed * (-forward * Math.sin(yaw) + sideways * Math.cos(yaw));
        target[2] += speed * (-forward * Math.cos(yaw) - sideways * Math.sin(yaw));
        target[1] += speed * (Number(keys.has("e")) - Number(keys.has("q")));
    }

    function forgetSection(section) {
        deleteMesh(gl, section?.opaque);
        deleteMesh(gl, section?.translucent);
    }

    function sectionIndex(x, y, z) {
        return x + sectionSize[0] * (z + sectionSize[2] * y);
    }

    function markCell(x, y, z) {
        if (x < 0 || x >= scene.size[0] || y < 0 || y >= scene.size[1] || z < 0 || z >= scene.size[2]) return;
        dirty.add(sectionIndex(Math.floor(x / SECTION), Math.floor(y / SECTION), Math.floor(z / SECTION)));
    }

    function rebuildDirtySections() {
        for (const id of dirty) {
            const x = id % sectionSize[0];
            const plane = Math.floor(id / sectionSize[0]);
            const z = plane % sectionSize[2];
            const y = Math.floor(plane / sectionSize[2]);
            forgetSection(sections.get(id));
            const section = meshSection(gl, scene, models, [x * SECTION, y * SECTION, z * SECTION]);
            if (section.opaque || section.translucent) sections.set(id, section);
            else sections.delete(id);
        }
        dirty.clear();
    }

    function paint(time) {
        frame = 0;
        if (lost) return;
        if (keys.size && lastFrame) move(Math.min(0.06, (time - lastFrame) / 1000));
        lastFrame = keys.size ? time : 0;
        const scale = Math.min(window.devicePixelRatio || 1, 2);
        const width = Math.max(1, Math.round(canvas.clientWidth * scale));
        const height = Math.max(1, Math.round(canvas.clientHeight * scale));
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width;
            canvas.height = height;
        }
        gl.viewport(0, 0, width, height);
        gl.clearColor(0.68, 0.76, 0.82, 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        if (!scene) return;

        rebuildDirtySections();
        const {right, up, back} = cameraBasis(yaw, pitch);
        const eye = target.map((coordinate, axis) => coordinate + back[axis] * distance);
        const far = Math.hypot(...eye) + sceneSpan * 2;
        gl.useProgram(program.program);
        atlas.bind();
        gl.uniform1i(program.atlas, 0);
        gl.uniform1f(program.alphaCutoff, 0.5);
        gl.uniformMatrix4fv(program.projection, false, perspective(width / height, far));
        gl.uniformMatrix4fv(program.view, false, viewMatrix(eye, right, up, back));
        gl.enableVertexAttribArray(program.position);
        gl.enableVertexAttribArray(program.color);
        gl.enable(gl.CULL_FACE);
        gl.enable(gl.DEPTH_TEST);
        // A vanilla grass side can have a tinted overlay on its base face.
        gl.depthFunc(gl.LEQUAL);
        const transparent = [];
        for (const chunk of sections.values()) {
            drawMesh(gl, program, chunk.opaque);
            if (chunk.translucent) {
                const depth = dot(chunk.center, back);
                transparent.push({mesh: chunk.translucent, depth});
            }
        }
        if (transparent.length) {
            transparent.sort((a, b) => a.depth - b.depth);
            gl.enable(gl.BLEND);
            gl.uniform1f(program.alphaCutoff, 1 / 255);
            gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
            gl.depthMask(false);
            for (const entry of transparent) drawMesh(gl, program, entry.mesh);
            gl.depthMask(true);
            gl.disable(gl.BLEND);
        }
        if (keys.size) schedule();
    }

    function resetView() {
        if (!scene || lost) return;
        const {min, max} = occupiedBounds(scene, models);
        target = min.map((coordinate, axis) => (coordinate + max[axis]) / 2);
        yaw = 2.39;
        pitch = 0.49;
        const {right, up, back} = cameraBasis(yaw, pitch);
        const aspect = Math.max(0.25, canvas.clientWidth / Math.max(canvas.clientHeight, 1));
        const halfHeight = Math.tan(VERTICAL_FOV / 2) * 0.88;
        distance = 1;
        // Fit the projected corners while leaving a margin for camera movement.
        for (const x of [min[0], max[0]]) {
            for (const y of [min[1], max[1]]) {
                for (const z of [min[2], max[2]]) {
                    const corner = [x - target[0], y - target[1], z - target[2]];
                    const width = Math.abs(dot(right, corner)) / (halfHeight * aspect);
                    const height = Math.abs(dot(up, corner)) / halfHeight;
                    distance = Math.max(distance, dot(back, corner) + Math.max(width, height));
                }
            }
        }
        schedule();
    }

    function setScene(next) {
        const preserveView = scene && next.size.every((size, axis) =>
            scene.size[axis] === size && scene.origin[axis] === next.origin[axis]);
        scene = next;
        sceneSpan = Math.hypot(...scene.size);
        sectionSize = scene.size.map(size => Math.ceil(size / SECTION));
        if (lost) return;
        models = scene.palette.map(model => prepareModel(model, atlas));
        for (const section of sections.values()) forgetSection(section);
        sections.clear();
        dirty.clear();
        const count = sectionSize[0] * sectionSize[1] * sectionSize[2];
        for (let id = 0; id < count; id++) dirty.add(id);
        if (!preserveView) resetView();
        schedule();
    }

    function update(indices) {
        if (!scene || lost) return;
        for (let id = models.length; id < scene.palette.length; id++) {
            models.push(prepareModel(scene.palette[id], atlas));
        }
        const [width, height, length] = scene.size;
        for (const index of indices) {
            if (!Number.isInteger(index) || index < 0 || index >= width * length * height) continue;
            const x = index % width;
            const plane = Math.floor(index / width);
            const z = plane % length;
            const y = Math.floor(plane / length);
            markCell(x, y, z);
            // Only section edges can affect faces in another mesh.
            if (x % SECTION === 0) markCell(x - 1, y, z);
            if (x % SECTION === SECTION - 1) markCell(x + 1, y, z);
            if (y % SECTION === 0) markCell(x, y - 1, z);
            if (y % SECTION === SECTION - 1) markCell(x, y + 1, z);
            if (z % SECTION === 0) markCell(x, y, z - 1);
            if (z % SECTION === SECTION - 1) markCell(x, y, z + 1);
        }
        schedule();
    }

    function listen(name, callback, options) {
        canvas.addEventListener(name, callback, {...options, signal: events.signal});
    }

    listen("pointerdown", event => {
        if (drag || event.button > 2) return;
        canvas.focus({preventScroll: true});
        canvas.setPointerCapture(event.pointerId);
        drag = {id: event.pointerId, x: event.clientX, y: event.clientY, pan: event.shiftKey || event.button !== 0};
        event.preventDefault();
    });
    listen("pointermove", event => {
        if (!drag || drag.id !== event.pointerId) return;
        const dx = event.clientX - drag.x;
        const dy = event.clientY - drag.y;
        drag.x = event.clientX;
        drag.y = event.clientY;
        if (drag.pan) {
            const {right, up} = cameraBasis(yaw, pitch);
            const scale = 2 * distance * Math.tan(VERTICAL_FOV / 2) / Math.max(canvas.clientHeight, 1);
            target = target.map((coordinate, axis) => coordinate + (-dx * right[axis] + dy * up[axis]) * scale);
        } else {
            yaw -= dx * 0.008;
            pitch = clamp(pitch + dy * 0.008, -1.55, 1.55);
        }
        schedule();
    });
    listen("lostpointercapture", event => {
        if (drag?.id === event.pointerId) drag = undefined;
    });
    listen("contextmenu", event => event.preventDefault());
    listen("wheel", event => {
        event.preventDefault();
        const multiplier = event.deltaMode === 1 ? 16 : (event.deltaMode === 2 ? canvas.clientHeight : 1);
        distance = clamp(distance * Math.exp(clamp(event.deltaY * multiplier * 0.0012, -1, 1)), 0.30, sceneSpan * 30);
        schedule();
    }, {passive: false});
    listen("keydown", event => {
        const key = event.key.toLowerCase();
        if (event.altKey || event.ctrlKey || event.metaKey || !MOVEMENT_KEYS.has(key)) return;
        event.preventDefault();
        if (!keys.has(key)) {
            keys.add(key);
            move(0.025);
        }
        schedule();
    });
    listen("keyup", event => keys.delete(event.key.toLowerCase()));
    listen("blur", () => keys.clear());
    listen("webglcontextlost", event => {
        event.preventDefault();
        lost = true;
        atlas.dispose();
    });
    listen("webglcontextrestored", () => {
        program = createProgram(gl);
        atlas = createTextureAtlas(gl, schedule);
        sections.clear();
        lost = false;
        if (scene) setScene(scene);
        else schedule();
    });
    window.addEventListener("resize", schedule, {signal: events.signal});
    window.addEventListener("blur", () => keys.clear(), {signal: events.signal});
    schedule();

    return {
        setScene,
        update,
        resetView,
        dispose() {
            cancelAnimationFrame(frame);
            resize.disconnect();
            events.abort();
            for (const section of sections.values()) forgetSection(section);
            atlas.dispose();
            gl.deleteProgram(program.program);
        },
    };
}
