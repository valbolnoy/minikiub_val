package io.learnk8s.knote;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class KnoteApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnoteApplication.class, args);
    }

}

interface NotesRepository extends MongoRepository<Note, String> {

}

@Document(collection = "notes")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
class Note {
    @Id
    private String id;
    private String description;

    @Override
    public String toString() {
        return description;
    }
}

@ConfigurationProperties(prefix = "knote")
class KnoteProperties {

    @Value("${minio.host:localhost}")
    private String minioHost;

    @Value("${minio.bucket:image-storage}")
    private String minioBucket;

    @Value("${minio.access.key:}")
    private String minioAccessKey;

    @Value("${minio.secret.key:}")
    private String minioSecretKey;

    @Value("${minio.useSSL:false}")
    private boolean minioUseSSL;

    @Value("${minio.reconnect.enabled:true}")
    private boolean minioReconnectEnabled;

    public String getMinioHost() {
        return minioHost;
    }

    public String getMinioBucket() {
        return minioBucket;
    }

    public String getMinioAccessKey() {
        return minioAccessKey;
    }

    public String getMinioSecretKey() {
        return minioSecretKey;
    }

    public boolean isMinioUseSSL() {
        return minioUseSSL;
    }

    public boolean isMinioReconnectEnabled() {
        return minioReconnectEnabled;
    }
}

@Controller
@EnableConfigurationProperties(KnoteProperties.class)
class KNoteController {

    @Autowired
    private NotesRepository notesRepository;
    @Autowired
    private KnoteProperties properties;

    private Parser parser = Parser.builder().build();
    private HtmlRenderer renderer = HtmlRenderer.builder().build();

    private MinioClient minioClient;

    @PostConstruct
    public void init() throws InterruptedException {
        initMinio();
    }

    @GetMapping("/")
    public String index(Model model) {
        getAllNotes(model);
        return "index";
    }

    @PostMapping("/note")
    public String saveNotes(@RequestParam("image") MultipartFile file,
                            @RequestParam String description,
                            @RequestParam(required = false) String publish,
                            @RequestParam(required = false) String upload,
                            Model model) throws Exception {

        if (publish != null && publish.equals("Publish")) {
            saveNote(description, model);
            getAllNotes(model);
            return "redirect:/";
        }
        if (upload != null && upload.equals("Upload")) {
            if (file != null && file.getOriginalFilename() != null &&
                    !file.getOriginalFilename().isEmpty()) {
                uploadImage(file, description, model);
            }
            getAllNotes(model);
            return "index";
        }
        return "index";
    }

    @GetMapping("/img/{name}")
    public ResponseEntity<byte[]> getImageByName(@PathVariable String name) throws Exception {
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(properties.getMinioBucket())
                        .object(name)
                        .build())) {
            String contentType = response.headers().get("Content-Type");
            MediaType mediaType = contentType != null
                    ? MediaType.parseMediaType(contentType)
                    : MediaType.IMAGE_PNG;
            return ResponseEntity.ok().contentType(mediaType).body(response.readAllBytes());
        }
    }

    private void getAllNotes(Model model) {
        List<Note> notes = notesRepository.findAll();
        Collections.reverse(notes);
        model.addAttribute("notes", notes);
    }

    private void initMinio() throws InterruptedException {
        boolean success = false;
        while (!success) {
            try {
                minioClient = MinioClient.builder()
                        .endpoint("http://" + properties.getMinioHost() + ":9000")
                        .credentials(properties.getMinioAccessKey(), properties.getMinioSecretKey())
                        .build();
                // Check if the bucket already exists.
                boolean isExist = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.getMinioBucket()).build());
                if (isExist) {
                    System.out.println("> Bucket already exists.");
                } else {
                    minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(properties.getMinioBucket()).build());
                }
                success = true;
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("> Minio Reconnect: " + properties.isMinioReconnectEnabled());
                if (properties.isMinioReconnectEnabled()) {
                    Thread.sleep(5000);
                } else {
                    success = true;
                }
            }
        }
        System.out.println("> Minio initialized!");
    }

    private void uploadImage(MultipartFile file, String description, Model model) throws Exception {
        String original = file.getOriginalFilename();
        String extension = original.contains(".") ? original.substring(original.lastIndexOf('.')) : "";
        String fileId = UUID.randomUUID().toString() + extension;
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(properties.getMinioBucket())
                        .object(fileId)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build());
        model.addAttribute("description",
                description + " ![](/img/" + fileId + ")");
    }

    private void saveNote(String description, Model model) {
        if (description != null && !description.trim().isEmpty()) {
            //We need to translate markup to HTML
            Node document = parser.parse(description.trim());
            String html = renderer.render(document);
            notesRepository.save(new Note(null, html));
            //After publish you need to clean up the textarea
            model.addAttribute("description", "");
        }
    }

}