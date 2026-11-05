#![cfg(windows)]
use lanvault_platform_windows::{protect, unprotect, DirectoryGuard};
use std::{fs, io::Write};

#[test]
fn publish_is_exact_no_replace_and_ancestors_stay_locked() -> Result<(), Box<dyn std::error::Error>>
{
    let temp = tempfile::tempdir()?;
    let root = temp.path().join("archive");
    fs::create_dir(&root)?;
    let guard = DirectoryGuard::open(&root)?;
    let reopened = DirectoryGuard::open(guard.path())?;
    assert_eq!(reopened.identity()?, guard.identity()?);
    drop(reopened);
    assert!(fs::rename(&root, temp.path().join("replacement")).is_err());
    let staging = guard.child(&["staging"])?;
    let target = guard.child(&["saved"])?;
    let mut first = staging.create_file("first.part")?;
    first.write_all(b"original\0\xff")?;
    first.sync_all()?;
    target.publish(&first, "точное имя.heic")?;
    drop(first);
    assert_eq!(
        fs::read(root.join("saved/точное имя.heic"))?,
        b"original\0\xff"
    );
    let mut second = staging.create_file("second.part")?;
    second.write_all(b"must not replace")?;
    assert!(target.publish(&second, "точное имя.heic").is_err());
    drop(second);
    assert_eq!(
        fs::read(root.join("saved/точное имя.heic"))?,
        b"original\0\xff"
    );
    drop(target);
    drop(staging);
    drop(guard);
    fs::rename(&root, temp.path().join("replacement"))?;
    Ok(())
}

#[test]
fn hard_links_and_alternate_stream_names_are_rejected() -> Result<(), Box<dyn std::error::Error>> {
    let temp = tempfile::tempdir()?;
    let guard = DirectoryGuard::open(temp.path())?;
    fs::write(temp.path().join("original"), b"keep")?;
    fs::hard_link(
        temp.path().join("original"),
        temp.path().join("linked.part"),
    )?;
    assert!(guard.open_file("linked.part", true).is_err());
    assert!(guard.create_file("original:stream").is_err());
    assert!(guard.child(&[".."]).is_err());
    Ok(())
}

#[test]
fn dpapi_round_trip_detects_tampering() -> Result<(), Box<dyn std::error::Error>> {
    let clear = b"synthetic private signing key";
    let mut protected = protect(clear)?;
    assert!(!protected.windows(clear.len()).any(|v| v == clear));
    assert_eq!(unprotect(&protected)?, clear);
    let last = protected.len() - 1;
    protected[last] ^= 1;
    assert!(unprotect(&protected).is_err());
    assert_eq!(unprotect(&protect(&[])?)?, Vec::<u8>::new());
    Ok(())
}

#[test]
fn real_windows_network_policy_query_is_parseable() -> Result<(), Box<dyn std::error::Error>> {
    let interfaces = lanvault_platform_windows::network_interfaces()?;
    // A host with no qualifying network must remain closed, rather than inventing one.
    for interface in interfaces {
        if interface.allowed() {
            assert!(interface.physical);
            assert_eq!(interface.category, "Private");
        }
    }
    Ok(())
}
