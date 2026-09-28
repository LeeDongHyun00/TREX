"""앱 정적 메시를 Blender로 열어 같은 재질·시점으로 국소 표면을 검토한다."""
import bpy, json, base64, sys
from pathlib import Path
from mathutils import Vector
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
args = sys.argv[sys.argv.index('--') + 1:]
source = ROOT / args[0]
out = ROOT / args[1]
out.mkdir(parents=True, exist_ok=True)
d = json.loads(source.read_text(encoding='utf-8').removeprefix('window.TREX_ATLAS=').removesuffix(';'))
bpy.ops.wm.read_factory_settings(use_empty=True)
for p in d['parts']:
    vertices = np.frombuffer(base64.b64decode(p['p']), '<i2').reshape(-1, 3).astype(float) / d['scale']
    vertices = vertices[:, [0, 2, 1]] * [1, -1, 1]
    faces = np.frombuffer(base64.b64decode(p['i']), '<u2').reshape(-1, 3)
    mesh = bpy.data.meshes.new(p['name'])
    mesh.from_pydata(vertices.tolist(), [], faces.tolist()); mesh.update()
    obj = bpy.data.objects.new(p['name'], mesh); bpy.context.collection.objects.link(obj)
    for key in ['group', 'muscle', 'side']: obj[key] = p[key] or ''
    for polygon in mesh.polygons: polygon.use_smooth = True
s = bpy.context.scene
s.render.engine = 'BLENDER_WORKBENCH'; s.render.resolution_x = 700; s.render.resolution_y = 700; s.render.resolution_percentage = 100
s.display.render_aa = '16'; sh = s.display.shading
sh.light = 'STUDIO'; sh.color_type = 'SINGLE'; sh.single_color = (.64, .64, .64)
sh.show_shadows = True; sh.show_cavity = False; sh.background_type = 'WORLD'
s.world = bpy.data.worlds.new('배경'); s.world.color = (.045, .048, .052)
s.view_settings.view_transform = 'Standard'; s.view_settings.look = 'None'
cam = bpy.data.objects.new('표면 검토 카메라', bpy.data.cameras.new('카메라')); s.collection.objects.link(cam); s.camera = cam
cam.data.type = 'ORTHO'
views = [
    ('pelvis-back', (0, .04, .89), (0, 3, 0), .37),
    ('pelvis-oblique', (0, .04, .85), (2, 3, -.3), .34),
    ('pelvis-front', (0, 0, .87), (0, -3, 0), .37),
    ('hand-front', (.206, .015, .795), (0, -3, 0), .25),
    ('hand-back', (.206, .015, .795), (0, 3, 0), .25),
    ('hand-outer', (.206, .015, .795), (3, 0, 0), .25),
]
for name, target, offset, scale in views:
    center = Vector(target); cam.location = center + Vector(offset)
    cam.rotation_euler = (center - cam.location).to_track_quat('-Z', 'Y').to_euler(); cam.data.ortho_scale = scale
    s.render.filepath = str(out / (name + '.png')); bpy.ops.render.render(write_still=True)
bpy.ops.wm.save_as_mainfile(filepath=str(out / 'trex-app-muscles.blend'))
