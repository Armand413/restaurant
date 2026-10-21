package bf.restaurant;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.util.UUID;

@Entity
public class Dish {
    @Id public UUID id;
    @NotBlank @Size(max=160) public String name;
    @NotNull @Size(max=2000) public String description="";
    @NotNull @Size(max=80) public String category="";
    @Min(0) @Max(100000000) public long price;
    public boolean available=true;
    @Column(name="image_url") @Size(max=500) @Pattern(regexp="/api/media/[0-9a-f-]{36}\\.(png|jpg)") public String imageUrl;
    @Version public long version;
}
