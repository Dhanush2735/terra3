package net.buildtheearth.terraminusminus.dataset;

import java.util.concurrent.CompletableFuture;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;

/**
 * A dataset consisting of floating-point scalar values.
 *
 * Terra3 only keeps the builtin Terra121 climate/soil scalar datasets. The
 * legacy configurable HTTP/tile scalar implementation was pruned, so this
 * interface intentionally has no Jackson default implementation annotation.
 *
 * The upstream bulk-sampling overload {@code getAsync(CornerBoundingBox2d, int, int)}
 * was also removed: it existed to fill a whole tile of samples in one call for the
 * pruned vector/OSM pipeline, and Terra3's only consumer
 * ({@code TerraBiomeProvider.queryData}) samples one point at a time through
 * {@link #getAsync(double, double)}. Dropping it removed the last dependency on
 * {@code CornerBoundingBox2d} and the {@code util.bvh} package.
 */
public interface IScalarDataset {
    default CompletableFuture<Double> getAsync(@NonNull double[] point)
            throws OutOfProjectionBoundsException {
        return this.getAsync(point[0], point[1]);
    }

    CompletableFuture<Double> getAsync(double lon, double lat)
        throws OutOfProjectionBoundsException;
}
