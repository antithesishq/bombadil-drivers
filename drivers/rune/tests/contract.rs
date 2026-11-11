use std::io::Cursor;

use bombadil_rune::Driver;
use proptest::prelude::*;
use rune::{AppSpec, Cx, El, Env, Runtime, col, el, text};
use serde_json::{Value, json};

fn view(count: &u32, _: &mut Cx<u32>) -> El {
    col()
        .w_full()
        .h_full()
        .child(text(format!("Count: {count}")).key("count"))
        .child(
            el().key("increment")
                .size(100.0, 40.0)
                .on_press(|count: &mut u32| *count += 1),
        )
        .child(
            el().key("reset")
                .size(100.0, 40.0)
                .on_press(|count: &mut u32| *count = 0),
        )
}

fn runtime() -> Runtime<u32> {
    let spec = AppSpec {
        on_key: Some(|count: &mut u32, event, _| match &event.key {
            rune::Key::Text(text) if text == "i" => {
                *count += 1;
                true
            }
            _ => false,
        }),
        ..AppSpec::new("Contract", (320.0, 240.0), || 0, view)
    };
    rune::App::new(spec).headless_runtime((320.0, 240.0), Env::new(true, 1.0))
}

fn messages(output: Vec<u8>) -> Vec<Value> {
    String::from_utf8(output)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

fn count(state: &Value) -> u32 {
    let label = state["state"]["elements"]
        .as_array()
        .unwrap()
        .iter()
        .find(|element| element["key"] == "count")
        .unwrap()["text"]
        .as_str()
        .unwrap();
    label.strip_prefix("Count: ").unwrap().parse().unwrap()
}

fn session(actions: &[Value]) -> Vec<Value> {
    let mut input = String::from("\n  \n");
    for action in actions {
        input.push_str(&json!({"type":"apply_action", "action":action}).to_string());
        input.push('\n');
    }
    input.push_str("{\"type\":\"terminate\"}\nthis must not be read\n");
    let mut output = Vec::new();
    Driver::new(runtime(), |_, _| true)
        .run("contract", Cursor::new(input), &mut output)
        .unwrap();
    messages(output)
}

#[test]
fn handshake_eof_and_termination() {
    let expected = json!({"type":"hello", "protocol":0, "name":"contract"});
    let result = session(&[]);
    assert_eq!(result.len(), 2);
    assert_eq!(result[0], expected);
    let initial = &result[1];
    assert_eq!(initial["type"], "state");
    assert_eq!(initial["settled"], true);
    assert_eq!(initial["state"]["viewport"], json!([320.0, 240.0]));
    assert_eq!(initial["state"]["accessibility"], Value::Null);
    assert!(initial.get("not_applied").is_none());
    assert!(initial.get("screenshot_png").is_none());
    assert!(initial["timestamp_us"].as_u64().unwrap() > 0);
    assert_eq!(count(initial), 0);
    let mut output = Vec::new();
    Driver::new(runtime(), |_, _| true)
        .run("contract", Cursor::new(""), &mut output)
        .unwrap();
    assert_eq!(messages(output).len(), 2);
}

#[test]
fn invalid_envelopes_are_fatal_not_rejected_actions() {
    for input in [
        "not JSON\n",
        "{\"type\":\"unknown\"}\n",
        "{\"type\":\"apply\",\"action\":{}}\n",
        "{\"type\":\"apply_action\"}\n",
    ] {
        let mut output = Vec::new();
        assert!(
            Driver::new(runtime(), |_, _| true)
                .run("contract", Cursor::new(input), &mut output)
                .is_err()
        );
        let result = messages(output);
        assert_eq!(result.len(), 3);
        assert_eq!(result[2]["type"], "error");
        assert!(!result[2]["message"].as_str().unwrap().is_empty());
    }
}

#[test]
fn rejected_actions_preserve_counter_and_session_continues() {
    let result = session(&[
        json!({"Tap":{"key":"missing"}}),
        json!({"Click":{"x":320,"y":20}}),
        json!({"Click":{"x":-1,"y":20}}),
        json!({"Scroll":{"x":0,"y":240,"dy":1}}),
        json!({"Key":{"name":"unsupported"}}),
        json!({"Tap":{"key":"increment","extra":true}}),
        json!({"unknown":{}}),
        json!({"Tap":{"key":"increment"}}),
    ]);
    for state in &result[2..9] {
        assert_eq!(count(state), 0);
        assert!(!state["not_applied"].as_str().unwrap().is_empty());
    }
    assert_eq!(count(&result[9]), 1);
    assert!(result[9].get("not_applied").is_none());
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]
    #[test]
    fn input_sequences_follow_the_counter_model(sequence in prop::collection::vec(0u8..7, 0..48)) {
        let mut initial = Driver::new(runtime(), |_, _| true);
        initial.observe().unwrap();
        let (x, y) = initial.runtime().find("increment").unwrap();
        let actions: Vec<_> = sequence.iter().map(|action| match action {
            0 => json!({"Tap":{"key":"increment"}}),
            1 => json!({"Click":{"x":x,"y":y}}),
            2 => json!({"Key":{"name":"i"}}),
            3 => json!({"TypeText":{"text":"ii"}}),
            4 => json!({"Tap":{"key":"reset"}}),
            5 => json!({"Tap":{"key":"missing"}}),
            _ => json!({"Scroll":{"x":300,"y":200,"dy":20}}),
        }).collect();
        let result = session(&actions);
        prop_assert_eq!(result.len(), sequence.len() + 2);
        let mut expected = 0;
        let mut timestamp = result[1]["timestamp_us"].as_u64().unwrap();
        for (action, state) in sequence.iter().zip(&result[2..]) {
            match action {
                0..=2 => expected += 1,
                3 => expected += 2,
                4 => expected = 0,
                _ => {},
            }
            prop_assert_eq!(count(state), expected);
            prop_assert_eq!(state.get("not_applied").is_some(), *action == 5);
            prop_assert_eq!(state["settled"].as_bool(), Some(true));
            let next = state["timestamp_us"].as_u64().unwrap();
            prop_assert!(next >= timestamp);
            timestamp = next;
        }
    }
}

#[test]
fn application_work_is_polled_with_frame_time_and_must_be_drawn() {
    let mut elapsed = 0.0;
    let mut delivered = false;
    let mut driver = Driver::new(runtime(), move |runtime, dt| {
        elapsed += dt;
        if !delivered && elapsed >= 0.05 {
            delivered = true;
            runtime.update_if(|count, _| {
                *count = 7;
                true
            });
            return false; // The model changed; require another Rune frame.
        }
        delivered
    });
    driver.set_frame_limit(1);
    let mut output = Vec::new();
    driver.run("pending", Cursor::new(""), &mut output).unwrap();
    assert_eq!(messages(output)[1]["settled"], false);
    driver.set_frame_limit(600);
    let mut output = Vec::new();
    driver.run("ready", Cursor::new(""), &mut output).unwrap();
    let state = &messages(output)[1];
    assert_eq!(state["settled"], true);
    assert_eq!(count(state), 7);
}

#[test]
fn motion_exhausts_budget_without_snapping_then_recovers() {
    use std::time::Duration;
    fn moving(tween: &rune::Tween<[f32; 4]>, _: &mut Cx<rune::Tween<[f32; 4]>>) -> El {
        el().key("bar").size(100.0, 20.0).live_rect(tween)
    }
    let tween = rune::Tween::linear(
        [0.0, 0.0, 100.0, 20.0],
        [0.0, 0.0, 0.0, 20.0],
        Duration::from_secs(1),
    );
    let runtime = Runtime::new(tween, moving, (320.0, 240.0), Env::new(true, 1.0));
    let mut driver = Driver::new(runtime, |_, _| true);
    driver.set_frame_limit(1);
    let state = driver.observe().unwrap();
    assert_eq!(state["settled"], false);
    let width = state["state"]["elements"][0]["rect"][2].as_f64().unwrap();
    assert!(width > 0.0 && width < 100.0, "width: {width}");
    driver.set_frame_limit(600);
    assert_eq!(driver.observe().unwrap()["settled"], true);
}

#[test]
fn future_rune_wake_is_not_mistaken_for_quiescence() {
    use std::time::Duration;
    fn hover(_: &bool, _: &mut Cx<bool>) -> El {
        el().key("zone")
            .size(100.0, 100.0)
            .on_hover_change(|entered: &mut bool, inside, _| *entered = inside)
            .hover_delays(Duration::from_millis(40), Duration::ZERO)
    }
    let mut runtime = Runtime::new(false, hover, (320.0, 240.0), Env::new(true, 1.0));
    runtime.frame(0.0);
    runtime.pointer_move(300.0, 200.0);
    runtime.frame(0.0);
    runtime.pointer_move(10.0, 10.0);
    let mut driver = Driver::new(runtime, |_, _| true);
    driver.set_frame_limit(0);
    assert_eq!(driver.observe().unwrap()["settled"], false);
    assert!(!driver.runtime().state);
    driver.set_frame_limit(600);
    assert_eq!(driver.observe().unwrap()["settled"], true);
    assert!(driver.runtime().state);
}

#[test]
fn transparent_screenshots_fail_explicitly_before_gpu_setup() {
    let mut runtime = runtime();
    runtime.transparent = true;
    assert!(
        Driver::new(runtime, |_, _| true)
            .with_screenshots()
            .is_err()
    );
}

#[test]
#[ignore = "requires an offscreen GPU backend (Vulkan/lavapipe on Linux) and fonts"]
fn opaque_screenshots_decode_and_change_with_counter() {
    use base64::Engine;
    let mut output = Vec::new();
    Driver::new(runtime(), |_, _| true)
        .with_screenshots()
        .unwrap()
        .run(
            "screenshots",
            Cursor::new(
                "{\"type\":\"apply_action\",\"action\":{\"Tap\":{\"key\":\"increment\"}}}\n",
            ),
            &mut output,
        )
        .unwrap();
    let result = messages(output);
    let pixels: Vec<_> = result[1..]
        .iter()
        .map(|state| {
            let png = base64::prelude::BASE64_STANDARD
                .decode(state["screenshot_png"].as_str().unwrap())
                .unwrap();
            let mut decoder = png::Decoder::new(Cursor::new(png)).read_info().unwrap();
            let mut pixels = vec![0; decoder.output_buffer_size().unwrap()];
            let info = decoder.next_frame(&mut pixels).unwrap();
            assert_eq!((info.width, info.height), (320, 240));
            assert_eq!(info.color_type, png::ColorType::Rgba);
            pixels
        })
        .collect();
    assert_eq!(count(&result[1]), 0);
    assert_eq!(count(&result[2]), 1);
    assert_ne!(pixels[0], pixels[1]);
}
