"""Keep simulation processes from outliving the worker that started them.

Three guards, any one of which is enough:
- the child watches a pipe from its parent and exits when it closes (every platform; in
  ``child.py``);
- on Linux the child asks the kernel for SIGKILL when its parent dies (``PR_SET_PDEATHSIG``);
- on Windows the parent puts each child in a Job Object that kills its processes when the last
  handle to it closes, which happens when the parent ends, however it ends.

In containers, run the worker under an init process (``init: true`` in Compose) so killed
children are reaped.

This module is imported by the simulation process, so it imports nothing heavy.
"""

from __future__ import annotations

import ctypes
import logging
import os
import signal
import sys
import threading
from typing import Any

log = logging.getLogger("archsim.jobs")

EXIT_ORPHANED = 70
"""Exit code of a simulation process that stopped because its worker was gone."""

PR_SET_PDEATHSIG = 1


def die_with_parent(parent_pid: int) -> None:
    """In the child: on Linux, be killed when the parent dies; exit now if it already has."""
    if not sys.platform.startswith("linux"):
        return
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        libc.prctl(PR_SET_PDEATHSIG, signal.SIGKILL, 0, 0, 0)
    except (OSError, AttributeError):  # pragma: no cover - not glibc/musl
        return
    if os.getppid() != parent_pid:  # the parent died before prctl took effect
        os._exit(EXIT_ORPHANED)


if sys.platform == "win32":
    from ctypes import wintypes

    _JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    _JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9
    _PROCESS_SET_QUOTA = 0x0100
    _PROCESS_TERMINATE = 0x0001

    class _IoCounters(ctypes.Structure):
        _fields_ = [
            ("ReadOperationCount", ctypes.c_ulonglong),
            ("WriteOperationCount", ctypes.c_ulonglong),
            ("OtherOperationCount", ctypes.c_ulonglong),
            ("ReadTransferCount", ctypes.c_ulonglong),
            ("WriteTransferCount", ctypes.c_ulonglong),
            ("OtherTransferCount", ctypes.c_ulonglong),
        ]

    class _BasicLimits(ctypes.Structure):
        _fields_ = [
            ("PerProcessUserTimeLimit", ctypes.c_int64),
            ("PerJobUserTimeLimit", ctypes.c_int64),
            ("LimitFlags", wintypes.DWORD),
            ("MinimumWorkingSetSize", ctypes.c_size_t),
            ("MaximumWorkingSetSize", ctypes.c_size_t),
            ("ActiveProcessLimit", wintypes.DWORD),
            ("Affinity", ctypes.c_size_t),
            ("PriorityClass", wintypes.DWORD),
            ("SchedulingClass", wintypes.DWORD),
        ]

    class _ExtendedLimits(ctypes.Structure):
        _fields_ = [
            ("BasicLimitInformation", _BasicLimits),
            ("IoInfo", _IoCounters),
            ("ProcessMemoryLimit", ctypes.c_size_t),
            ("JobMemoryLimit", ctypes.c_size_t),
            ("PeakProcessMemoryUsed", ctypes.c_size_t),
            ("PeakJobMemoryUsed", ctypes.c_size_t),
        ]

    _kernel32: Any = ctypes.WinDLL("kernel32", use_last_error=True)
    _kernel32.CreateJobObjectW.argtypes = (wintypes.LPVOID, wintypes.LPCWSTR)
    _kernel32.CreateJobObjectW.restype = wintypes.HANDLE
    _kernel32.SetInformationJobObject.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        wintypes.LPVOID,
        wintypes.DWORD,
    )
    _kernel32.SetInformationJobObject.restype = wintypes.BOOL
    _kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    _kernel32.OpenProcess.restype = wintypes.HANDLE
    _kernel32.AssignProcessToJobObject.argtypes = (wintypes.HANDLE, wintypes.HANDLE)
    _kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
    _kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    _kernel32.CloseHandle.restype = wintypes.BOOL

    class KillOnCloseJob:
        """A Windows Job Object with ``JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE``: closing it, or
        ending the process that holds it, kills every process added to it."""

        def __init__(self) -> None:
            handle = _kernel32.CreateJobObjectW(None, None)
            if not handle:
                raise ctypes.WinError(ctypes.get_last_error())
            info = _ExtendedLimits()
            info.BasicLimitInformation.LimitFlags = _JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if not _kernel32.SetInformationJobObject(
                handle,
                _JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                ctypes.byref(info),
                ctypes.sizeof(info),
            ):
                error = ctypes.get_last_error()
                _kernel32.CloseHandle(handle)
                raise ctypes.WinError(error)
            self._handle: int | None = handle

        def add(self, pid: int) -> None:
            if self._handle is None:
                raise OSError("the job is closed")
            process = _kernel32.OpenProcess(_PROCESS_SET_QUOTA | _PROCESS_TERMINATE, False, pid)
            if not process:
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                if not _kernel32.AssignProcessToJobObject(self._handle, process):
                    raise ctypes.WinError(ctypes.get_last_error())
            finally:
                _kernel32.CloseHandle(process)

        def close(self) -> None:
            """Kill every process in the job."""
            if self._handle is not None:
                _kernel32.CloseHandle(self._handle)
                self._handle = None

    _job: KillOnCloseJob | None = None
    _job_lock = threading.Lock()

    def _adopt(pid: int) -> None:
        global _job
        with _job_lock:
            if _job is None:
                _job = KillOnCloseJob()
            job = _job
        job.add(pid)


def adopt(pid: int) -> bool:
    """In the parent, right after starting a child: on Windows, put it in this process's
    kill-on-close job. The job is never closed here; Windows closes it when this process ends,
    which kills any simulation process still running. Returns whether the child was added."""
    if sys.platform == "win32":  # a positive check, so type checkers on Linux skip it
        try:
            _adopt(pid)
        except OSError as exc:  # the pipe watchdog still guards this child
            log.warning("could not tie simulation process %d to this worker: %s", pid, exc)
            return False
        return True
    return False
