package com.angryguyy.metadatastripper.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * High-performance, Zero-GC implementation of the Amanatides & Woo "Fast Voxel Traversal Algorithm".
 * <p>
 * This class calculates the exact sequence of 3D grid blocks (voxels) intersected by a ray
 * originating from a start point to an end point (or given a direction and distance).
 * <p>
 * <b>Performance:</b> To completely eliminate Garbage Collection overhead during millions of
 * asynchronous ray-trace calculations, this iterator never instantiates new coordinate arrays.
 * Instead, it swaps between two internal pre-allocated buffers ({@code ref} and {@code refSwap}).
 * <p>
 * <i>Reference: Amanatides, J., & Woo, A. A Fast Voxel Traversal Algorithm for Ray Tracing.</i>
 */
public final class BlockIterator implements Iterator<int[]> {

    private int x;
    private int y;
    private int z;

    private int stepX;
    private int stepY;
    private int stepZ;

    private double tMax;
    private double tMaxX;
    private double tMaxY;
    private double tMaxZ;
    private double tDeltaX;
    private double tDeltaY;
    private double tDeltaZ;

    private int[] ref = new int[3];
    private int[] refSwap = new int[3];
    private int[] next;

    public BlockIterator(double startX, double startY, double startZ, double endX, double endY, double endZ) {
        initialize(startX, startY, startZ, endX, endY, endZ);
    }

    public BlockIterator(int x, int y, int z, double startX, double startY, double startZ, double endX, double endY, double endZ) {
        initialize(x, y, z, startX, startY, startZ, endX, endY, endZ);
    }

    public BlockIterator(double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance) {
        initialize(startX, startY, startZ, directionX, directionY, directionZ, distance);
    }

    public BlockIterator(int x, int y, int z, double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance) {
        initialize(x, y, z, startX, startY, startZ, directionX, directionY, directionZ, distance);
    }

    public BlockIterator(double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance, boolean normalized) {
        if (normalized) {
            initializeNormalized(startX, startY, startZ, directionX, directionY, directionZ, distance);
        } else {
            initialize(startX, startY, startZ, directionX, directionY, directionZ, distance);
        }
    }

    public BlockIterator(int x, int y, int z, double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance, boolean normalized) {
        if (normalized) {
            initializeNormalized(x, y, z, startX, startY, startZ, directionX, directionY, directionZ, distance);
        } else {
            initialize(x, y, z, startX, startY, startZ, directionX, directionY, directionZ, distance);
        }
    }

    public BlockIterator initialize(double startX, double startY, double startZ, double endX, double endY, double endZ) {
        return initialize(floor(startX), floor(startY), floor(startZ), startX, startY, startZ, endX, endY, endZ);
    }

    public BlockIterator initialize(int x, int y, int z, double startX, double startY, double startZ, double endX, double endY, double endZ) {
        double directionX = endX - startX;
        double directionY = endY - startY;
        double directionZ = endZ - startZ;
        double distance = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        double fixedDistance = distance == 0. ? Double.NaN : distance;

        directionX /= fixedDistance;
        directionY /= fixedDistance;
        directionZ /= fixedDistance;

        return initializeNormalized(x, y, z, startX, startY, startZ, directionX, directionY, directionZ, distance);
    }

    public BlockIterator initialize(double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance) {
        return initialize(floor(startX), floor(startY), floor(startZ), startX, startY, startZ, directionX, directionY, directionZ, distance);
    }

    public BlockIterator initialize(int x, int y, int z, double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance) {
        double signum = Math.signum(distance);
        directionX *= signum;
        directionY *= signum;
        directionZ *= signum;
        double length = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);

        if (length == 0.) {
            length = Double.NaN;
        }

        directionX /= length;
        directionY /= length;
        directionZ /= length;

        return initializeNormalized(x, y, z, startX, startY, startZ, directionX, directionY, directionZ, Math.abs(distance));
    }

    public BlockIterator initializeNormalized(double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance) {
        return initializeNormalized(floor(startX), floor(startY), floor(startZ), startX, startY, startZ, directionX, directionY, directionZ, Math.abs(distance));
    }

    public BlockIterator initializeNormalized(int x, int y, int z, double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double distance) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.tMax = distance;

        this.stepX = directionX < 0. ? -1 : 1;
        this.stepY = directionY < 0. ? -1 : 1;
        this.stepZ = directionZ < 0. ? -1 : 1;

        int boundX = x + (stepX > 0 ? 1 : 0);
        int boundY = y + (stepY > 0 ? 1 : 0);
        int boundZ = z + (stepZ > 0 ? 1 : 0);

        this.tMaxX = directionX == 0. ? Double.POSITIVE_INFINITY : (boundX - startX) / directionX;
        this.tMaxY = directionY == 0. ? Double.POSITIVE_INFINITY : (boundY - startY) / directionY;
        this.tMaxZ = directionZ == 0. ? Double.POSITIVE_INFINITY : (boundZ - startZ) / directionZ;

        this.tDeltaX = 1. / Math.abs(directionX);
        this.tDeltaY = 1. / Math.abs(directionY);
        this.tDeltaZ = 1. / Math.abs(directionZ);

        this.next = ref;
        this.ref[0] = x;
        this.ref[1] = y;
        this.ref[2] = z;

        return this;
    }

    public int[] calculateNext() {
        boolean advanced = false;

        if (tMaxX < tMaxY) {
            if (tMaxZ < tMaxX) {
                if (tMaxZ <= tMax) {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                    advanced = true;
                }
            } else {
                if (tMaxX <= tMax) {
                    if (tMaxZ == tMaxX) {
                        z += stepZ;
                        tMaxZ += tDeltaZ;
                    }
                    x += stepX;
                    tMaxX += tDeltaX;
                    advanced = true;
                }
            }
        } else if (tMaxY < tMaxZ) {
            if (tMaxY <= tMax) {
                if (tMaxX == tMaxY) {
                    x += stepX;
                    tMaxX += tDeltaX;
                }
                y += stepY;
                tMaxY += tDeltaY;
                advanced = true;
            }
        } else {
            if (tMaxZ <= tMax) {
                if (tMaxX == tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                }
                if (tMaxY == tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                }
                z += stepZ;
                tMaxZ += tDeltaZ;
                advanced = true;
            }
        }

        if (advanced) {
            ref[0] = x;
            ref[1] = y;
            ref[2] = z;
        } else {
            next = null;
        }

        return next;
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public int[] next() {
        int[] result = this.next;
        if (result == null) {
            throw new NoSuchElementException();
        }

        int[] temp = ref;
        ref = refSwap;
        refSwap = temp;
        this.next = ref;

        calculateNext();
        return result;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }
}