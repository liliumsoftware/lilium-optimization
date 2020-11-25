package ir.baho.algorithm.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Base of the GeoJSON object hierarchy: carries the optional {@code crs} and {@code bbox} common to all
 * geometries and drives Jackson polymorphic (de)serialization by the JSON {@code "type"} property. Unknown
 * properties are ignored so responses from varied providers deserialize leniently.
 */
@JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({
        @JsonSubTypes.Type(Point.class),
        @JsonSubTypes.Type(MultiPoint.class),
        @JsonSubTypes.Type(LineString.class)
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Setter
@Getter
public class GeoJsonObject implements Serializable {

    private Crs crs;
    private double[] bbox;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GeoJsonObject that = (GeoJsonObject) o;
        if (!Objects.equals(crs, that.crs)) {
            return false;
        }
        return Arrays.equals(bbox, that.bbox);
    }

    @Override
    public int hashCode() {
        int result = crs != null ? crs.hashCode() : 0;
        result = 31 * result + (bbox != null ? Arrays.hashCode(bbox) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "GeoJsonObject{}";
    }

}
