#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 2 && args[1] == "--repair-network" {
        if let Err(error) = aether_gui_lib::routing::repair_cli() {
            eprintln!("{error}");
            std::process::exit(3);
        }
        return;
    }
    if args.len() == 3 && args[1] == "--routing-helper" {
        if let Err(error) = aether_gui_lib::routing::helper_main(std::path::Path::new(&args[2])) {
            eprintln!("{error}");
            std::process::exit(2);
        }
        return;
    }
    if args.len() == 3 && args[1] == "--repair-network" {
        if let Err(error) = aether_gui_lib::routing::repair_main(std::path::Path::new(&args[2])) {
            eprintln!("{error}");
            std::process::exit(3);
        }
        return;
    }
    aether_gui_lib::run();
}
