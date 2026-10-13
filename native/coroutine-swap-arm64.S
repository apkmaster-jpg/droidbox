/*
 * coroutine-swap-arm64.S -- raw coroutine stack switching for AArch64.
 *
 * Part of DroidBox's QEMU backend (GPL-2.0-or-later, like the util/
 * coroutine code it serves; approach derived from QubeVM's Android port).
 *
 * QubeVM's Android patch rewrites util/coroutine-sigaltstack.c to avoid
 * libc signal/ucontext calls (missing or unreliable on bionic, and a
 * source of PAC-related crashes on Snapdragon SoCs) and instead calls two
 * raw primitives declared in that file:
 *
 *   extern void coro_swap(void **save_sp, void *new_sp);
 *   extern void *coro_bootstrap_init(void *stack_top,
 *                                    void (*entry)(void *), void *arg);
 *
 * The published QubeVM patch references this file from util/meson.build
 * but does not ship it; this is a clean-room implementation of the same
 * contract, verified against the call sites in coroutine-sigaltstack.c:
 *
 * - coro_swap() saves the callee-saved integer registers (x19-x30 per
 *   AAPCS64; x18 is Android's reserved platform register and is never
 *   touched) plus SP into the frame it leaves on the current stack,
 *   stores SP to *save_sp, loads SP from new_sp, restores the register
 *   frame found there and returns to the restored link register.
 * - coro_bootstrap_init() fabricates exactly such a frame on a fresh,
 *   16-byte-aligned stack so that the first coro_swap() into it starts
 *   executing entry(arg): the fabricated x19/x20 carry entry/arg and the
 *   fabricated link register points at the local bootstrap trampoline,
 *   which moves arg into x0 and branches to entry.
 *
 * Only plain RET/BR instructions are used -- no PAC-authenticating
 * variants -- because a fabricated frame cannot carry valid pointer
 * authentication signatures. This is what dodges the Snapdragon PAC
 * crashes noted in coroutine-sigaltstack.c.
 *
 * NOTE: only the AArch64 variant is provided; the x86_64 host variant
 * (coroutine-swap-x86_64.S) is not needed for Android arm64 hosts.
 */

    .text
    .align 2

/* void coro_swap(void **save_sp, void *new_sp);
 *   x0 = &save slot, x1 = new stack pointer. */
    .globl coro_swap
    .type coro_swap, %function
coro_swap:
    /* Push x19-x30, keeping 16-byte SP alignment (96 bytes). */
    stp x19, x20, [sp, #-96]!
    stp x21, x22, [sp, #16]
    stp x23, x24, [sp, #32]
    stp x25, x26, [sp, #48]
    stp x27, x28, [sp, #64]
    stp x29, x30, [sp, #80]
    mov x2, sp              /* x2 = sp (STR cannot use SP directly) */
    str x2, [x0]            /* *save_sp = sp (frame base) */
    mov sp, x1              /* switch to the new stack */
    ldp x29, x30, [sp, #80]
    ldp x27, x28, [sp, #64]
    ldp x25, x26, [sp, #48]
    ldp x23, x24, [sp, #32]
    ldp x21, x22, [sp, #16]
    ldp x19, x20, [sp], #96
    ret
    .size coro_swap, .-coro_swap

/* Local bootstrap trampoline (not global): entered by RET from coro_swap()
 * on a fresh stack with x19 = entry, x20 = arg. */
coro_bootstrap_trampoline:
    mov x0, x20
    br x19

/* void *coro_bootstrap_init(void *stack_top, void (*entry)(void *), void *arg);
 *   Returns the initial SP for the new coroutine's stack. */
    .globl coro_bootstrap_init
    .type coro_bootstrap_init, %function
coro_bootstrap_init:
    /* x0 = 16-aligned stack top; reserve the 96-byte register frame. */
    sub x0, x0, #96
    stp x1, x2, [x0]        /* x19 = entry, x20 = arg */
    stp xzr, xzr, [x0, #16] /* x21, x22 */
    stp xzr, xzr, [x0, #32] /* x23, x24 */
    stp xzr, xzr, [x0, #48] /* x25, x26 */
    stp xzr, xzr, [x0, #64] /* x27, x28 */
    adr x3, coro_bootstrap_trampoline
    stp xzr, x3, [x0, #80]  /* x29 = 0, x30 = bootstrap trampoline */
    ret
    .size coro_bootstrap_init, .-coro_bootstrap_init

    .section .note.GNU-stack,"",@progbits
