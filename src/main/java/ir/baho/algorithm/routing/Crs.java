package ir.baho.algorithm.routing;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A GeoJSON coordinate reference system: a {@link CrsType} plus free-form properties (e.g. an EPSG name).
 */
@Setter
@Getter
public class Crs implements Serializable {

    private CrsType type = CrsType.NAME;
    private Map<String, Object> properties = new HashMap<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Crs)) {
            return false;
        }
        Crs crs = (Crs) o;
        if (!Objects.equals(properties, crs.properties)) {
            return false;
        }
        return Objects.equals(type, crs.type);
    }

    @Override
    public int hashCode() {
        int result = type != null ? type.hashCode() : 0;
        result = 31 * result + (properties != null ? properties.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Crs{" + "type='" + type + '\'' + ", properties=" + properties + '}';
    }

}
