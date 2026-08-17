package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ImageConverterSuggestionTest {
    @Test
    void extremePortraitSuggestionNeverFallsBelowBuilderLimits() throws Exception {
        Path dir=Files.createTempDirectory("image2schem-suggest-");
        Path file=dir.resolve("portrait.png");
        try {
            BufferedImage image=new BufferedImage(120,1600,BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(image,"png",file.toFile()));
            ImageConverter.Suggestion s=ImageConverter.suggest(file);
            assertTrue(s.width()>=64 && s.width()<=176,"suggested width must match generic builder/command limits");
            assertTrue(s.depth()>=24 && s.depth()<=120,"suggested depth must match generic builder/command limits");
            assertEquals(112,s.height(),"very tall previews should remain capped");
            assertEquals(120,s.sourceWidth());
            assertEquals(1600,s.sourceHeight());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
