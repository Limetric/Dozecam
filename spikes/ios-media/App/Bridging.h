#include <vlc/vlc.h>
#include <mach/mach.h>
#include <os/proc.h>

/// Resident footprint (what jetsam counts), in bytes. A C shim because Swift 6
/// rejects the `mach_task_self_` global as concurrency-unsafe.
static inline uint64_t spike_phys_footprint(void) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) != KERN_SUCCESS) return 0;
    return info.phys_footprint;
}
