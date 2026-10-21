package bf.restaurant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DishRepository extends JpaRepository<Dish,UUID> {
    org.springframework.data.domain.Page<Dish> findByNameContainingIgnoreCase(String name,org.springframework.data.domain.Pageable pageable);
}
