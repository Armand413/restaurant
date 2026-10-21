package bf.restaurant;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import static bf.restaurant.ApiErrors.require;

@RestController
@RequestMapping("/api/media")
class MediaController {
    private final Path directory;
    MediaController(@Value("${app.media-dir}") String directory) { this.directory=Path.of(directory).toAbsolutePath().normalize(); }
    @PostMapping @PreAuthorize("hasRole('ADMIN')") Object upload(@RequestParam("file") MultipartFile file) throws Exception {
        require(file.getSize()>0&&file.getSize()<=5*1024*1024,400,"Image de 5 Mo maximum");byte[] bytes=file.getBytes();
        boolean png=bytes.length>8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});boolean jpg=bytes.length>3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;
        require(png||jpg,400,"Formats acceptés : JPEG et PNG");String name=UUID.randomUUID()+(png?".png":".jpg");Files.createDirectories(directory);Files.write(directory.resolve(name),bytes,StandardOpenOption.CREATE_NEW);return Map.of("url","/api/media/"+name);
    }
    @GetMapping("/{name}") ResponseEntity<byte[]> get(@PathVariable String name) throws Exception {
        require(name.matches("[0-9a-f-]{36}\\.(png|jpg)"),404,"Image introuvable");Path path=directory.resolve(name);require(Files.isRegularFile(path),404,"Image introuvable");return ResponseEntity.ok().contentType(name.endsWith("png")?MediaType.IMAGE_PNG:MediaType.IMAGE_JPEG).header("X-Content-Type-Options","nosniff").body(Files.readAllBytes(path));
    }
}
