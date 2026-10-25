//! Platform channel messages: answering what the framework sends, and
//! encoding what the driver sends.
//!
//! The driver implements no plugins. It answers every method call with a
//! successful null result, so that framework code awaiting a reply doesn't
//! hang or throw `MissingPluginException`, and keeps the few things it
//! needs: the current route (`flutter/navigation`), the text input client
//! (`flutter/textinput`), the clipboard and exit requests
//! (`flutter/platform`).

use serde_json::{json, Value};

/// What the driver learnt from the framework's platform messages.
#[derive(Debug, Default)]
pub struct Platform {
    /// The route the app last reported, for apps using `Router` or named
    /// routes.
    pub route: Option<String>,
    /// The client ID of the text field connected to text input, if any.
    pub text_client: Option<i64>,
    pub clipboard: Option<String>,
    /// Whether the app asked to close (for example on back from its first
    /// page) since the driver last reported it.
    pub exit_requested: bool,
}

impl Platform {
    /// Handles a message from the framework and returns the reply.
    pub fn handle(&mut self, channel: &str, message: &[u8]) -> Vec<u8> {
        let call = serde_json::from_slice::<Value>(message)
            .ok()
            .filter(|v| v.get("method").is_some_and(Value::is_string));
        let Some(call) = call else {
            return if channel.starts_with("dev.flutter.pigeon.") {
                // Pigeon host APIs: a successful null result, `[null]`.
                vec![12, 1, 0]
            } else if message.first() == Some(&7) {
                // StandardMethodCodec calls start with the method name, a
                // string (type 7): a successful null result.
                vec![0, 0]
            } else {
                // Messages without a reply.
                vec![]
            };
        };
        let method = call["method"].as_str().unwrap_or_default();
        let args = &call["args"];
        let result = match (channel, method) {
            ("flutter/navigation", "routeInformationUpdated") => {
                if let Some(route) = args
                    .get("uri")
                    .or_else(|| args.get("location"))
                    .and_then(Value::as_str)
                {
                    self.route = Some(route.to_string());
                }
                Value::Null
            }
            ("flutter/textinput", "TextInput.setClient") => {
                self.text_client = args[0].as_i64();
                Value::Null
            }
            ("flutter/textinput", "TextInput.clearClient") => {
                self.text_client = None;
                Value::Null
            }
            ("flutter/platform", "SystemNavigator.pop") => {
                self.exit_requested = true;
                Value::Null
            }
            ("flutter/platform", "Clipboard.setData") => {
                self.clipboard = args["text"].as_str().map(str::to_string);
                Value::Null
            }
            ("flutter/platform", "Clipboard.getData") => match &self.clipboard {
                Some(text) => json!({"text": text}),
                None => Value::Null,
            },
            ("flutter/platform", "Clipboard.hasStrings") => {
                json!({"value": self.clipboard.as_ref().is_some_and(|t| !t.is_empty())})
            }
            _ => Value::Null,
        };
        serde_json::to_vec(&json!([result])).unwrap()
    }
}

/// A JSON method call, as `JSONMethodCodec` encodes it.
pub fn json_method_call(method: &str, args: Value) -> Vec<u8> {
    serde_json::to_vec(&json!({"method": method, "args": args})).unwrap()
}

/// Asks the framework to replace the text of the connected text field.
pub fn update_editing_state(client: i64, text: &str) -> Vec<u8> {
    let end = text.encode_utf16().count();
    json_method_call(
        "TextInputClient.updateEditingState",
        json!([client, {
            "text": text,
            "selectionBase": end,
            "selectionExtent": end,
            "selectionAffinity": "TextAffinity.downstream",
            "selectionIsDirectional": false,
            "composingBase": -1,
            "composingExtent": -1,
        }]),
    )
}

fn standard_size(out: &mut Vec<u8>, size: usize) {
    if size < 254 {
        out.push(size as u8);
    } else if size <= 0xffff {
        out.push(254);
        out.extend_from_slice(&(size as u16).to_le_bytes());
    } else {
        out.push(255);
        out.extend_from_slice(&(size as u32).to_le_bytes());
    }
}

/// A string, as `StandardMessageCodec` encodes it.
pub fn standard_string(text: &str) -> Vec<u8> {
    let mut out = vec![7];
    standard_size(&mut out, text.len());
    out.extend_from_slice(text.as_bytes());
    out
}

/// A 32-bit integer, as `StandardMessageCodec` encodes it.
pub fn standard_int(value: i32) -> Vec<u8> {
    let mut out = vec![3];
    out.extend_from_slice(&value.to_le_bytes());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_route_updates() {
        let mut platform = Platform::default();
        let reply = platform.handle(
            "flutter/navigation",
            &json_method_call(
                "routeInformationUpdated",
                json!({"uri": "/search", "state": null, "replace": false}),
            ),
        );
        assert_eq!(reply, b"[null]");
        assert_eq!(platform.route.as_deref(), Some("/search"));
    }

    #[test]
    fn tracks_the_text_input_client() {
        let mut platform = Platform::default();
        platform.handle(
            "flutter/textinput",
            &json_method_call("TextInput.setClient", json!([3, {}])),
        );
        assert_eq!(platform.text_client, Some(3));
        platform.handle(
            "flutter/textinput",
            &json_method_call("TextInput.clearClient", Value::Null),
        );
        assert_eq!(platform.text_client, None);
    }

    #[test]
    fn answers_clipboard_and_exit() {
        let mut platform = Platform::default();
        let has = |p: &mut Platform| {
            p.handle(
                "flutter/platform",
                &json_method_call("Clipboard.hasStrings", json!("text/plain")),
            )
        };
        assert_eq!(has(&mut platform), br#"[{"value":false}]"#);
        platform.handle(
            "flutter/platform",
            &json_method_call("Clipboard.setData", json!({"text": "hi"})),
        );
        assert_eq!(has(&mut platform), br#"[{"value":true}]"#);
        platform.handle(
            "flutter/platform",
            &json_method_call("SystemNavigator.pop", Value::Null),
        );
        assert!(platform.exit_requested);
    }

    #[test]
    fn answers_standard_method_calls_with_null_and_ignores_messages() {
        let mut platform = Platform::default();
        let call = [standard_string("activateSystemCursor"), vec![0]].concat();
        assert_eq!(platform.handle("flutter/mousecursor", &call), [0, 0]);
        assert_eq!(platform.handle("flutter/accessibility", &[13, 0]), [0u8; 0]);
        assert_eq!(
            platform.handle("dev.flutter.pigeon.url_launcher_linux.UrlLauncherApi.launchUrl", &[12, 1, 0]),
            [12, 1, 0]
        );
    }

    #[test]
    fn encodes_standard_values() {
        assert_eq!(standard_string("ab"), [7, 2, b'a', b'b']);
        let long = "x".repeat(300);
        assert_eq!(&standard_string(&long)[..4], [7, 254, 44, 1]);
        assert_eq!(standard_int(258), [3, 2, 1, 0, 0]);
    }
}
