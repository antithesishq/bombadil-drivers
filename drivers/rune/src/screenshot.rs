use anyhow::{Result, anyhow, ensure};
use rune::Runtime;
use rune::render::{Gpu, OFFSCREEN_FORMAT, Offscreen, Renderer};

pub(super) struct Screenshot {
    gpu: Gpu,
    renderer: Renderer,
    target: Offscreen,
}

impl Screenshot {
    pub fn new<S>(runtime: &Runtime<S>) -> Result<Self> {
        ensure!(
            !runtime.transparent,
            "transparent screenshots require Rune's private alpha-composition path"
        );
        let gpu = Gpu::try_new(Gpu::instance(), None).map_err(|error| anyhow!(error))?;
        let (width, height) = dimensions(runtime, gpu.device.limits().max_texture_dimension_2d)?;
        let renderer = Renderer::new(&gpu.device, OFFSCREEN_FORMAT);
        let target = Offscreen::new(&gpu.device, width, height);
        Ok(Self {
            gpu,
            renderer,
            target,
        })
    }

    pub fn capture<S>(&mut self, runtime: &mut Runtime<S>) -> Result<Vec<u8>> {
        ensure!(
            !runtime.transparent,
            "transparent screenshots require Rune's private alpha-composition path"
        );
        let size = dimensions(runtime, self.gpu.device.limits().max_texture_dimension_2d)?;
        let format = if runtime.srgb_blend() {
            OFFSCREEN_FORMAT.remove_srgb_suffix()
        } else {
            OFFSCREEN_FORMAT
        };
        if size != (self.target.width, self.target.height) || self.renderer.format() != format {
            self.renderer.set_format(&self.gpu.device, format);
            self.target = Offscreen::with_format(&self.gpu.device, size.0, size.1, format);
        }
        self.renderer.set_srgb_blend(runtime.srgb_blend());
        self.renderer
            .upload_images(&self.gpu.device, &self.gpu.queue, &mut runtime.images);
        self.renderer
            .prepare_surfaces(&self.gpu.device, &self.gpu.queue, &runtime.surfaces, size);
        self.renderer.prepare(
            &self.gpu.device,
            &self.gpu.queue,
            &mut runtime.text.atlas,
            &runtime.instances,
            size,
        );
        let mut encoder = self.gpu.device.create_command_encoder(&Default::default());
        self.renderer
            .draw(&mut encoder, &self.target.view, runtime.clear_color());
        self.target.copy(&mut encoder);
        self.renderer.submit(&self.gpu.queue, encoder);
        let rgba = self.target.read(&self.gpu.device);
        let mut png = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut png, size.0, size.1);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            encoder.set_source_srgb(png::SrgbRenderingIntent::Perceptual);
            encoder.write_header()?.write_image_data(&rgba)?;
        }
        Ok(png)
    }
}

fn dimensions<S>(runtime: &Runtime<S>, limit: u32) -> Result<(u32, u32)> {
    let width = (runtime.viewport.0 * runtime.env.scale).round();
    let height = (runtime.viewport.1 * runtime.env.scale).round();
    ensure!(
        width.is_finite()
            && height.is_finite()
            && width >= 1.0
            && height >= 1.0
            && width <= limit as f32
            && height <= limit as f32,
        "invalid screenshot dimensions"
    );
    Ok((width as u32, height as u32))
}
