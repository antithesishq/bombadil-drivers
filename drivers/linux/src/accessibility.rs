use std::collections::{HashSet, VecDeque};

use anyhow::{Context, Result, ensure};
use atspi::{
    AccessibilityConnection, CoordType, Interface, State,
    proxy::{accessible::AccessibleProxy, proxy_ext::ProxyExt},
    zbus::{fdo::DBusProxy, proxy::CacheProperties},
};

use crate::model::{Node, Segment};

pub struct Accessibility {
    connection: AccessibilityConnection,
}

impl Accessibility {
    pub async fn connect() -> Result<Self> {
        Ok(Self {
            connection: AccessibilityConnection::new()
                .await
                .context("connect to the AT-SPI accessibility bus")?,
        })
    }

    pub async fn nodes(&self, pid: u32) -> Result<Vec<Node>> {
        for attempt in 0..3 {
            let result = self.nodes_once(pid).await;
            let stale = result.as_ref().err().is_some_and(|error| {
                error.chain().any(|cause| {
                    matches!(cause.downcast_ref::<atspi::zbus::Error>(), Some(atspi::zbus::Error::MethodError(name, _, _))
                        if name.as_str() == "org.freedesktop.DBus.Error.UnknownMethod" || name.as_str() == "org.freedesktop.DBus.Error.UnknownObject")
                    || matches!(cause.downcast_ref::<atspi::zbus::Error>(), Some(atspi::zbus::Error::FDO(error))
                        if matches!(error.as_ref(), atspi::zbus::fdo::Error::UnknownMethod(_) | atspi::zbus::fdo::Error::UnknownObject(_)))
                    || matches!(cause.downcast_ref::<atspi::zbus::fdo::Error>(), Some(atspi::zbus::fdo::Error::UnknownMethod(_) | atspi::zbus::fdo::Error::UnknownObject(_)))
                    || matches!(cause.downcast_ref::<atspi::AtspiError>(), Some(atspi::AtspiError::Zbus(error))
                        if error.contains("OwnedErrorName(\"org.freedesktop.DBus.Error.UnknownMethod\")")
                            || error.contains("OwnedErrorName(\"org.freedesktop.DBus.Error.UnknownObject\")"))
                })
            });
            if !stale || attempt == 2 {
                return result;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
        unreachable!()
    }

    async fn nodes_once(&self, pid: u32) -> Result<Vec<Node>> {
        let bus = self.connection.connection();
        let registry = self.connection.root_accessible_on_registry().await?;
        let dbus = DBusProxy::new(bus).await?;
        let mut roots = Vec::new();
        for object in registry.get_children().await? {
            let Some(name) = object.name_as_str() else {
                continue;
            };
            let owner_pid =
                dbus.get_connection_unix_process_id(name.try_into()?).await;
            if owner_pid.is_ok_and(|owner| owner == pid) {
                roots.push(object);
            }
        }
        ensure!(
            roots.len() == 1,
            "expected one AT-SPI application for PID {pid}, found {}",
            roots.len()
        );
        let mut queue = VecDeque::from([(roots.remove(0), Vec::new())]);
        let mut seen = HashSet::new();
        let mut nodes = Vec::new();
        while !queue.is_empty() {
            let mut batch = Vec::new();
            for _ in 0..queue.len().min(16) {
                let (object, selector) = queue.pop_front().unwrap();
                ensure!(selector.len() < 64, "AT-SPI tree exceeds depth 64");
                if seen.insert(object.clone()) {
                    batch.push((object, selector));
                }
            }
            ensure!(
                nodes.len() + batch.len() <= 4096,
                "AT-SPI tree exceeds the PoC limit of 4096 nodes"
            );
            let results = futures::future::try_join_all(batch.into_iter().map(|(object, mut selector)| async move {
            let accessible = AccessibleProxy::builder(bus)
                .destination(
                    object.name_as_str().context("null AT-SPI object")?,
                )?
                .path(object.path_as_str())?
                .cache_properties(CacheProperties::No)
                .build()
                .await?;
            let (role, name, states, interfaces, children) = tokio::try_join!(
                accessible.get_role(),
                accessible.name(),
                accessible.get_state(),
                accessible.get_interfaces(),
                accessible.get_children(),
            )?;
            selector.push(Segment {
                role: format!("{role:?}"),
                name,
            });
            ensure!(
                !states.contains(State::Defunct),
                "AT-SPI node became defunct; retry from a clean run"
            );
            let proxies = accessible.proxies().await?;
            let bounds = if interfaces.contains(Interface::Component) {
                let (x, y, w, h) = proxies
                    .component()
                    .await?
                    .get_extents(CoordType::Window)
                    .await?;
                Some([x, y, w, h])
            } else {
                None
            };
            let actions = if interfaces.contains(Interface::Action) {
                let actions = proxies.action().await?.get_actions().await?;
                ensure!(actions.len() <= 64, "too many AT-SPI actions");
                actions.into_iter().map(|action| action.name).collect()
            } else {
                Vec::new()
            };
            let text = if interfaces.contains(Interface::Text)
                && role != atspi::Role::PasswordText
            {
                let text = proxies.text().await?;
                let count = text.character_count().await?;
                ensure!(
                    count <= 4096,
                    "text exceeds the PoC limit of 4096 characters"
                );
                Some(text.get_text(0, count).await?)
            } else {
                None
            };
            let node = Node {
                selector: selector.clone(),
                enabled: states.contains(State::Sensitive),
                showing: states.contains(State::Visible)
                    && states.contains(State::Showing),
                focused: states.contains(State::Focused),
                selected: states.contains(State::Selected),
                editable: states.contains(State::Editable)
                    && interfaces.contains(Interface::EditableText),
                actions,
                window_bounds: bounds,
                text,
            };
            Ok::<_, anyhow::Error>((node, children))
            })).await?;
            for (node, children) in results {
                for child in children {
                    if child.name_as_str().is_some() {
                        queue.push_back((child, node.selector.clone()));
                    }
                }
                nodes.push(node);
            }
        }
        Ok(nodes)
    }
}
