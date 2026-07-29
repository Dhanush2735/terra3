package net.buildtheearth.terraminusminus.dataset.builtin;

import java.util.concurrent.CompletableFuture;

import net.buildtheearth.terraminusminus.dataset.IScalarDataset;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;

/**
 * Abstract implementation of {@link IScalarDataset} for the builtin datasets.
 *
 * The upstream bulk-sampling override {@code getAsync(CornerBoundingBox2d, int, int)}
 * was removed along with that method on {@link IScalarDataset}; see the interface
 * for the rationale. Point sampling below is unchanged.
 *
 * @author DaPorkchop_
 */
public abstract class AbstractBuiltinDataset implements IScalarDataset {
    protected final double scaleX;
    protected final double scaleY;

    public AbstractBuiltinDataset(int samplesX, int samplesY) {
        this.scaleX = samplesX / 360.0d;
        this.scaleY = samplesY / 180.0d;
    }

    @Override
    public CompletableFuture<Double> getAsync(double lon, double lat) throws OutOfProjectionBoundsException {
        OutOfProjectionBoundsException.checkLongitudeLatitudeInRange(lon, lat);
        return CompletableFuture.completedFuture(this.get((lon + 180.0d) * this.scaleX, (90.0d - lat) * this.scaleY));
    }

    protected abstract double get(double x, double y);
}
