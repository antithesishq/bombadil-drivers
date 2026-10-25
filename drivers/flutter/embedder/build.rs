//! Generates bindings for `flutter_embedder.h` and links
//! `libflutter_engine.so`, both from the directory in `FLUTTER_EMBEDDER`
//! (the flake's dev shell sets it to the prebuilt embedder engine).
use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=FLUTTER_EMBEDDER");
    let dir = PathBuf::from(env::var("FLUTTER_EMBEDDER").expect(
        "set FLUTTER_EMBEDDER to the directory with flutter_embedder.h and \
         libflutter_engine.so (run inside `nix develop`)",
    ));
    let header = dir.join("flutter_embedder.h");
    println!("cargo:rerun-if-changed={}", header.display());
    println!("cargo:rustc-link-search=native={}", dir.display());
    println!("cargo:rustc-link-lib=dylib=flutter_engine");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{}", dir.display());

    bindgen::Builder::default()
        .header(header.to_string_lossy())
        .allowlist_function("Flutter.*")
        .allowlist_type("Flutter.*")
        .allowlist_var("kFlutter.*")
        .allowlist_var("FLUTTER_ENGINE_VERSION")
        .default_enum_style(bindgen::EnumVariation::Consts)
        .prepend_enum_name(false)
        .derive_default(true)
        .generate()
        .expect("generating bindings for flutter_embedder.h")
        .write_to_file(PathBuf::from(env::var("OUT_DIR").unwrap()).join("bindings.rs"))
        .expect("writing bindings");
}
