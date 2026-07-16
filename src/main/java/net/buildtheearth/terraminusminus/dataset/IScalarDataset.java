package net.buildtheearth.terraminusminus.dataset;

import java.util.concurrent.CompletableFuture;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;

/**
 * A dataset consisting of floating-point scalar values.
 *
 * Terra3 only keeps the builtin Terra121 climate/soil scalar datasets. The
 * legacy configurable HTTP/tile scalar implementation was pruned, so this
 * interface intentionally has no Jackson default implementation annotation.
 */
public interface IScalarDataset {
    default CompletableFuture<Double> getAsync(@NonNull double[] point)
            throws OutOfProjectionBoundsException {
        return this.getAsync(point[0], point[1]);
    }

    CompletableFuture<Double> getAsync(double lon, double lat)
        throws OutOfProjectionBoundsException;

    CompletableFuture<double[]> getAsync(@NonNull CornerBoundingBox2d bounds,
        int sizeX, int sizeZ) throws OutOfProjectionBoundsException;
}
