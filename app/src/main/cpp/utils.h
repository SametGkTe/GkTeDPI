#ifndef GKTE_UTILS_H
#define GKTE_UTILS_H

extern struct params default_params;
extern char default_tls_data[517];

void save_defaults(void);
void reset_params(void);
int parse_args(int argc, char **argv);
void store_allocated_args(char **argv, int argc);

#endif
