use rune::{AppSpec, Cx, El, col, el, text, tok};

#[derive(Default)]
struct State {
    count: u32,
}

fn view(state: &State, _: &mut Cx<State>) -> El {
    let theme = tok::theme(true);
    col()
        .w_full()
        .h_full()
        .center()
        .gap(16.0)
        .fill(theme.bg)
        .child(
            text(format!("Count: {}", state.count))
                .key("count")
                .font(tok::type_::TITLE)
                .color(theme.text),
        )
        .child(
            el().key("increment")
                .role("button")
                .label("Increment")
                .focusable(true)
                .size(160.0, 40.0)
                .radius(8.0)
                .fill(tok::accent(0, true))
                .on_press(|state: &mut State| state.count += 1)
                .child(text("Increment").color(theme.text)),
        )
        .child(
            el().key("reset")
                .role("button")
                .label("Reset")
                .focusable(true)
                .size(160.0, 40.0)
                .radius(8.0)
                .fill(theme.control_off)
                .on_press(|state: &mut State| state.count = 0)
                .child(text("Reset").color(theme.text)),
        )
}

fn spec() -> AppSpec<State> {
    AppSpec {
        scenes: &[("four", |state| state.count = 4)],
        on_key: Some(|state, event, _| match &event.key {
            rune::Key::Text(text) if text == "i" => {
                state.count += 1;
                true
            }
            _ => false,
        }),
        ..AppSpec::new("Counter", (320.0, 240.0), State::default, view)
    }
}

fn main() -> anyhow::Result<()> {
    // Native startup and UI are unchanged. Only this flag enables the adapter.
    if std::env::args().any(|arg| arg == "--bombadil") {
        let runtime =
            rune::App::new(spec()).headless_runtime((320.0, 240.0), rune::Env::new(true, 1.0));
        let mut driver = bombadil_rune::Driver::new(runtime, |_, _| true);
        if std::env::args().any(|arg| arg == "--screenshots") {
            driver = driver.with_screenshots()?;
        }
        driver.run(
            "rune-counter",
            std::io::stdin().lock(),
            std::io::stdout().lock(),
        )
    } else {
        rune::run(spec());
        Ok(())
    }
}
