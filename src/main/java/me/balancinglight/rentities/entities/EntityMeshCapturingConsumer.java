package me.balancinglight.rentities.entities;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

public class EntityMeshCapturingConsumer implements VertexConsumer {

    private final List<float[]> captured = new ArrayList<>(); //

    private float vx, vy, vz; //[cite: 1]
    private float vnx, vny, vnz; //[cite: 1]
    private float vu, vv; //[cite: 1]
    private int currentBone = 0; //[cite: 1]

    private float pivotX = 0, pivotY = 0, pivotZ = 0; //[cite: 1]
    private boolean hasPivot = false; //[cite: 1]

    public void setBone(int boneIndex) { //[cite: 1]
        this.currentBone = boneIndex; //[cite: 1]
    }

    public void setBonePivot(float px, float py, float pz) { //[cite: 1]
        this.pivotX = px; //[cite: 1]
        this.pivotY = py; //[cite: 1]
        this.pivotZ = pz; //[cite: 1]
        this.hasPivot = true; //[cite: 1]
    }

    public void clearBonePivot() { //[cite: 1]
        this.pivotX = 0; //[cite: 1]
        this.pivotY = 0; //[cite: 1]
        this.pivotZ = 0; //[cite: 1]
        this.hasPivot = false; //[cite: 1]
    }

    public int capturedVertexCount() { //[cite: 1]
        return captured.size(); //[cite: 1]
    }

    public float[] bakeAndReset() {
        // Preserve Minecraft's vertex order exactly. UVs are attached to the
        // corresponding vertex, so reversing a quad here mirrors/flips the texture.
        float[] result = new float[captured.size() * 9];
        int offset = 0;
        for (float[] vertex : captured) {
            System.arraycopy(vertex, 0, result, offset, 9);
            offset += 9;
        }
        captured.clear();
        return result;
    }

    public void reset() { //[cite: 1]
        captured.clear(); //[cite: 1]
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) { //[cite: 1]
        this.vx = x; //[cite: 1]
        this.vy = y; //[cite: 1]
        this.vz = z; //[cite: 1]
        this.lastNormalMatrix.identity(); //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer addVertex(com.mojang.blaze3d.vertex.PoseStack.Pose pose, float x, float y, float z) { //[cite: 1]
        org.joml.Vector4f pos = new org.joml.Vector4f(x, y, z, 1.0f).mul(pose.pose()); //[cite: 1]
        this.vx = pos.x; //[cite: 1]
        this.vy = pos.y; //[cite: 1]
        this.vz = pos.z; //[cite: 1]
        this.lastNormalMatrix = pose.normal(); //[cite: 1]
        return this; //[cite: 1]
    }

    private org.joml.Matrix3f lastNormalMatrix = new org.joml.Matrix3f(); //[cite: 1]

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) { //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer setColor(int packedArgb) { //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer setUv(float u, float v) { //[cite: 1]
        this.vu = u; //[cite: 1]
        this.vv = v; //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer setUv1(int u, int v) { //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer setUv2(int u, int v) { //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer setLineWidth(float width) { //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public VertexConsumer setNormal(float nx, float ny, float nz) { //[cite: 1]
        org.joml.Vector3f norm = new org.joml.Vector3f(nx, ny, nz).mul(lastNormalMatrix); //[cite: 1]
        this.vnx = norm.x; //[cite: 1]
        this.vny = norm.y; //[cite: 1]
        this.vnz = norm.z; //[cite: 1]
        
        float fx = hasPivot ? vx - pivotX : vx; //[cite: 1]
        float fy = hasPivot ? vy - pivotY : vy; //[cite: 1]
        float fz = hasPivot ? vz - pivotZ : vz; //[cite: 1]
        
        captured.add(new float[]{fx, fy, fz, vnx, vny, vnz, vu, vv, currentBone}); //[cite: 1]
        return this; //[cite: 1]
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) { //[cite: 1]
        float fx = hasPivot ? x - pivotX : x; //[cite: 1]
        float fy = hasPivot ? y - pivotY : y; //[cite: 1]
        float fz = hasPivot ? z - pivotZ : z; //[cite: 1]
        captured.add(new float[]{fx, fy, fz, nx, ny, nz, u, v, currentBone}); //[cite: 1]
    }
}
