import argparse
import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, GLib


parser = argparse.ArgumentParser()
parser.add_argument("--broken-cancel", action="store_true")
parser.add_argument("--broken-text", action="store_true")
options = parser.parse_args()

GLib.set_prgname("bombadil-desktop-fixture")
GLib.set_application_name("Bombadil desktop fixture")

window = Gtk.Window(title="Bombadil desktop fixture")
window.set_default_size(480, 240)
window.connect("destroy", Gtk.main_quit)

box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
box.set_border_width(24)
window.add(box)

entry = Gtk.Entry()
entry.get_accessible().set_name("Test input")
box.pack_start(entry, False, False, 0)


def corrupt_text(widget):
    widget.handler_block(text_handler)
    widget.set_text("BROKEN")
    widget.handler_unblock(text_handler)


if options.broken_text:
    text_handler = entry.connect("changed", corrupt_text)


def open_dialog(_):
    dialog = Gtk.Dialog(
        title="Test dialog", transient_for=window, modal=True
    )
    dialog.add_button("Cancel", Gtk.ResponseType.CANCEL)
    dialog.get_content_area().add(Gtk.Label(label="Cancel should close this dialog."))
    if not options.broken_cancel:
        dialog.connect("response", lambda widget, _: widget.destroy())
    dialog.show_all()


button = Gtk.Button(label="Open dialog")
button.connect("clicked", open_dialog)
box.pack_start(button, False, False, 0)

window.show_all()
Gtk.main()
